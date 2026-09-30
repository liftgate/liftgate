package dev.liftgate.build

import dev.liftgate.App
import dev.liftgate.db.Builds as BuildsTable
import dev.liftgate.db.Deployments as DeploymentsTable
import dev.liftgate.db.Environments
import dev.liftgate.db.Organizations
import dev.liftgate.db.Projects
import dev.liftgate.db.RegistryOrphans
import dev.liftgate.db.Services as ServicesTable
import dev.liftgate.db.sql
import dev.liftgate.deploy.Build
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.live
import dev.liftgate.deploy.toBuild
import dev.liftgate.http.json
import dev.liftgate.http.shaPattern
import dev.liftgate.org.toOrganization
import dev.liftgate.project.toEnvironment
import dev.liftgate.project.toProject
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.toService
import io.fabric8.kubernetes.client.KubernetesClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.time.Duration.Companion.days

private const val KEPT_BUILDS = 10
private val building = listOf(BuildStatus.QUEUED, BuildStatus.RUNNING).map { it.sql }
internal val manifestTypes = listOf(
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.docker.distribution.manifest.v2+json",
).joinToString()

fun JdbcTransaction.orphanRepositories(services: Op<Boolean>) {
    val repositories = (BuildsTable innerJoin ServicesTable innerJoin Environments innerJoin Projects).select(BuildsTable.imageRef)
        .where { services and BuildsTable.imageRef.isNotNull() }
        .mapNotNull { it[BuildsTable.imageRef]?.substringBeforeLast(':') }
        .distinct()
    RegistryOrphans.batchInsert(repositories, ignore = true) { this[RegistryOrphans.repository] = it }
}

/**
 * @author Dean
 * @date 9/27/2026
 */
class RegistryJanitor(private val app: App, private val kube: KubernetesClient) {
    private val log = LoggerFactory.getLogger(RegistryJanitor::class.java)
    private val registry = app.config.registry
    private val host = registry.substringBefore('/')
    private val origin = "${if (app.config.registryInsecure) "http" else "https"}://$host"

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            runCatching { log.info("registry janitor deleted {} manifests", runOnce()) }.onFailure { ensureActive(); log.warn("registry janitor failed", it) }
            delay(1.days)
        }
    }

    suspend fun runOnce(): Int {
        val shared = if (app.config.registryTokenAuth) null else runInterruptible(Dispatchers.IO) { sharedLogin() }
        suspend fun authorization(name: String) = if (app.config.registryTokenAuth) "Bearer ${token(name)}" else shared?.let { "Basic $it" }
        val repositories = app.db.tx { repositories() }
        val listed = repositories.filter { it.startsWith("$registry/") }.flatMap { repository ->
            val name = repository.removePrefix("$host/")
            val auth = authorization(name)
            send(HttpMethod.Get, "/v2/$name/tags/list", auth)?.body<Tags>()?.tags.orEmpty().map { tag ->
                val digest = send(HttpMethod.Head, "/v2/$name/manifests/$tag", auth)?.let { it.headers["Docker-Content-Digest"] ?: error("$host sent no digest for $name:$tag") }
                "$repository:$tag" to digest?.let { "$name@$it" }
            }
        }.toMap()
        val doomed = app.db.tx { prune(repositories, listed) }
        doomed.groupBy({ it.substringBefore('@') }, { it.substringAfter('@') }).forEach { (name, digests) ->
            val auth = authorization(name)
            digests.forEach { send(HttpMethod.Delete, "/v2/$name/manifests/$it", auth) }
        }
        return doomed.size
    }

    private fun JdbcTransaction.images(): Pair<Set<String>, List<Build>> {
        val services = (ServicesTable innerJoin Environments innerJoin Projects innerJoin Organizations).selectAll()
            .associate { it[ServicesTable.id] to ServiceScope(it.toService(), it.toEnvironment(), it.toProject(), it.toOrganization()) }
        val inFlight = BuildsTable.select(BuildsTable.serviceId, BuildsTable.commitSha).where { BuildsTable.status inList building }
            .mapNotNull { row -> services[row[BuildsTable.serviceId]]?.let { BuildJobs.imageRef(registry, it, row[BuildsTable.commitSha]) } }
        val succeeded = BuildsTable.selectAll()
            .where { (BuildsTable.status eq BuildStatus.SUCCEEDED.sql) and (BuildsTable.imagePruned eq false) and BuildsTable.imageRef.isNotNull() }
            .orderBy(BuildsTable.createdAt, SortOrder.DESC)
            .forUpdate()
            .map { it.toBuild() }
        val released = (DeploymentsTable innerJoin BuildsTable).select(BuildsTable.imageRef).where { DeploymentsTable.status inList live }.mapNotNull { it[BuildsTable.imageRef] }
        val serving = (DeploymentsTable innerJoin BuildsTable).select(BuildsTable.imageRef)
            .where { DeploymentsTable.reachedRunning eq true }
            .withDistinctOn(DeploymentsTable.serviceId to SortOrder.ASC)
            .orderBy(DeploymentsTable.createdAt, SortOrder.DESC)
            .mapNotNull { it[BuildsTable.imageRef] }
        val keep = (inFlight + released + serving + services.values.map { BuildJobs.imageRef(registry, it, "cache") } +
            succeeded.groupBy { it.serviceId }.values.flatMap { builds -> builds.take(KEPT_BUILDS).mapNotNull { it.imageRef } }).toSet()
        return keep to succeeded.filter { it.imageRef !in keep }
    }

    private fun JdbcTransaction.repositories(): Set<String> {
        val (keep, candidates) = images()
        return (keep + candidates.mapNotNull { it.imageRef }).map { it.substringBeforeLast(':') }.toSet() + RegistryOrphans.selectAll().map { it[RegistryOrphans.repository] }
    }

    private fun JdbcTransaction.prune(repositories: Set<String>, listed: Map<String, String?>): Set<String> {
        val (kept, candidates) = images()
        val keep = kept + listed.keys.filterNot { key -> key.substringAfterLast(':').let { it == "cache" || shaPattern.matches(it) } }
        val doomed = listed.filterKeys { it !in keep }.values.filterNotNull().toSet() - keep.mapNotNull { listed[it] }.toSet()
        val gone = candidates.filter { build -> build.imageRef?.let { it.startsWith("$registry/") && listed[it].let { digest -> digest == null || digest in doomed } } == true }
        BuildsTable.update({ BuildsTable.id inList gone.map { it.id } }) { it[imagePruned] = true }
        RegistryOrphans.deleteWhere { repository inList repositories.filter { name -> listed.keys.none { it.substringBeforeLast(':') == name && it !in keep } } }
        return doomed
    }

    private fun sharedLogin(): String? = kube.secrets().inNamespace(app.config.buildNamespace).withName(REGISTRY_SECRET).get()?.data?.get(BuildJobs.DOCKER_CONFIG_KEY)?.let {
        json.parseToJsonElement(Base64.getDecoder().decode(it).decodeToString()).jsonObject["auths"]?.jsonObject?.get(host)?.jsonObject?.get("auth")?.jsonPrimitive?.content
    }

    private suspend fun token(name: String): String {
        val response = app.http.get("${app.config.internalUrl}/api/v1/registry/token") {
            basicAuth(JANITOR_ACCOUNT, requireNotNull(app.config.registryJanitorPassword))
            parameter("scope", "repository:$name:pull,delete")
        }
        check(response.status.isSuccess()) { "the token service answered ${response.status} for $name" }
        return response.body<JsonObject>().getValue("token").jsonPrimitive.content
    }

    private suspend fun send(method: HttpMethod, path: String, authorization: String?): HttpResponse? {
        val response = app.http.request("$origin$path") {
            this.method = method
            authorization?.let { header(HttpHeaders.Authorization, it) }
            header(HttpHeaders.Accept, manifestTypes)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) { "$host answered ${method.value} $path with ${response.status}" }
        return response
    }

    @Serializable
    private data class Tags(val tags: List<String>? = null)
}
