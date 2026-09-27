package dev.liftgate.build

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.config.Config
import dev.liftgate.db.Builds as BuildsTable
import dev.liftgate.db.RegistryOrphans
import dev.liftgate.deploy.Builds
import dev.liftgate.deploy.Deployment
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.Deployments
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.json
import dev.liftgate.http.liftgate
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val LAYER = "layer"

/**
 * @author Dean
 * @date 9/27/2026
 */
@EnableKubernetesMockClient(crud = true)
class RegistryJanitorTest {
    lateinit var client: KubernetesClient

    private val db = TestDatabase.clean()
    private val services = Services(db)
    private val builds = Builds(db)
    private val deployments = Deployments(db)
    private val requests = mutableListOf<HttpRequestData>()
    private val registry = mutableMapOf<String, MutableMap<String, String>>()

    private fun janitor(config: Config, http: HttpClient = fake()) = RegistryJanitor(
        mockk<App> {
            every { this@mockk.config } returns config
            every { this@mockk.db } returns this@RegistryJanitorTest.db
            every { this@mockk.http } returns http
        },
        client,
    )

    private fun fake() = HttpClient(MockEngine { request ->
        requests += request
        val path = request.url.encodedPath.removePrefix("/v2/")
        val reference = path.substringAfter("/manifests/")
        val tags = registry[path.substringBefore("/tags/list").substringBefore("/manifests/")]
            ?: return@MockEngine respond("""{"errors":[{"code":"NAME_UNKNOWN"}]}""", HttpStatusCode.NotFound)
        when (request.method) {
            HttpMethod.Get -> respond(
                buildJsonObject { put("tags", JsonArray(tags.keys.map(::JsonPrimitive))) }.toString(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
            HttpMethod.Head -> tags[reference]?.let { respond("", HttpStatusCode.OK, headersOf("Docker-Content-Digest", it)) } ?: respond("", HttpStatusCode.NotFound)
            else -> respond("", if (tags.values.removeAll { it == reference }) HttpStatusCode.Accepted else HttpStatusCode.NotFound)
        }
    }) { install(ContentNegotiation) { json(json) } }

    private suspend fun environment(project: String = "shop"): UUID {
        val projects = Projects(db)
        val org = Orgs(db).bySlug("acme") ?: Orgs(db).create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id)
        return projects.environments(projects.create(org.id, project, project, "acme/$project", 42).id).single().id
    }

    private suspend fun succeed(buildId: UUID, image: String): Deployment =
        builds.markSucceeded(buildId, image).also { deployments.transition(it.id, DeploymentStatus.RUNNING) }

    private suspend fun release(serviceId: UUID, image: String) = succeed(builds.request(serviceId, image.substringAfterLast(':'), null, "main").id, image)

    private suspend fun pruned() = db.tx { BuildsTable.select(BuildsTable.commitSha).where { BuildsTable.imagePruned eq true }.map { it[BuildsTable.commitSha] }.toSet() }

    private suspend fun orphans() = db.tx { RegistryOrphans.selectAll().count() }

    private suspend fun assertPruned(deployment: Deployment) {
        val error = assertFailsWith<LiftgateException> { deployments.rollback(deployment.id) }
        assertEquals(HttpStatusCode.Conflict to "image_pruned", error.status to error.code)
    }

    @Test
    fun `only tags outside the keep-set are deleted, and only in repositories liftgate pushed to`() = runBlocking {
        val environment = environment()
        val api = services.create(environment, ServiceSpec("api", "API", ServiceKind.WEB)).id
        val gone = services.create(environment, ServiceSpec("gone", "Gone", ServiceKind.WORKER)).id
        release(api, "old.registry/acme/shop-api:b00")
        val released = (1..12).map { release(api, "registry.test/acme/shop-api:b%02d".format(it)) }
        deployments.transition(deployments.rollback(released[1].id).id, DeploymentStatus.RUNNING)
        builds.request(api, "c00", null, "main")
        release(gone, "registry.test/acme/shop-gone:g1")
        services.delete(gone)
        val blog = environment("blog")
        release(services.create(blog, ServiceSpec("web", "Web", ServiceKind.WEB)).id, "registry.test/acme/blog-web:w1")
        Projects(db).delete(requireNotNull(Projects(db).environment(blog)).projectId)
        registry["acme/shop-api"] = (1..12).associate { "b%02d".format(it) to "sha256:d%02d".format(it) }.toMutableMap()
            .apply { putAll(mapOf("c00" to "sha256:dc0", "cache" to "sha256:dcache", "stray" to "sha256:dstray", "twin" to "sha256:d05")) }
        registry["acme/shop-gone"] = mutableMapOf("g1" to "sha256:dg1", "cache" to "sha256:dgcache")
        registry["acme/blog-web"] = mutableMapOf("w1" to "sha256:dw1")
        registry["liftgate/control-plane"] = mutableMapOf("0.1.0" to "sha256:platform")
        val config = testConfig().copy(registry = "registry.test")

        assertEquals(5, janitor(config).runOnce())

        val deletes = requests.filter { it.method == HttpMethod.Delete }
        assertEquals(
            setOf(
                "acme/shop-api/manifests/sha256:d01",
                "acme/shop-api/manifests/sha256:dstray",
                "acme/shop-gone/manifests/sha256:dg1",
                "acme/shop-gone/manifests/sha256:dgcache",
                "acme/blog-web/manifests/sha256:dw1",
            ),
            deletes.map { it.url.encodedPath.removePrefix("/v2/") }.toSet(),
        )
        assertTrue(requests.none { "liftgate/" in it.url.encodedPath })
        assertEquals((2..12).map { "b%02d".format(it) }.toSet() + setOf("c00", "cache", "twin"), registry.getValue("acme/shop-api").keys)
        assertEquals(setOf("b01"), pruned())
        assertPruned(released[0])

        requests.clear()
        assertEquals(0, janitor(config).runOnce())
        assertTrue(requests.none { it.method == HttpMethod.Delete })
        assertEquals(0L, orphans())
    }

    @Test
    fun `shared registry auth logs in with the registry-credentials secret when there is one`() = runBlocking {
        release(services.create(environment(), ServiceSpec("api", "API", ServiceKind.WEB)).id, "registry.test/acme/shop-api:b01")
        registry["acme/shop-api"] = mutableMapOf("b01" to "sha256:d01")
        val config = testConfig().copy(registry = "registry.test")
        janitor(config).runOnce()
        assertTrue(requests.isNotEmpty() && requests.all { it.headers[HttpHeaders.Authorization] == null })

        requests.clear()
        val encoder = Base64.getEncoder()
        val login = encoder.encodeToString("ci:secret".toByteArray())
        val dockerConfig = encoder.encodeToString("""{"auths":{"registry.test":{"auth":"$login"}}}""".toByteArray())
        client.resource(SecretBuilder().withNewMetadata().withName(REGISTRY_SECRET).withNamespace(config.buildNamespace).endMetadata().addToData(BuildJobs.DOCKER_CONFIG_KEY, dockerConfig).build()).create()
        janitor(config).runOnce()
        assertEquals(setOf("Basic $login"), requests.map { it.headers[HttpHeaders.Authorization] }.toSet())
    }

    @Test
    fun `thirty deploys on distribution keep ten sha tags, the cache and the running image through garbage collection`() = runBlocking {
        val tokens = RegistryTokens(db, services, TestRegistry.config)
        val environment = environment("store")
        val api = services.create(environment, ServiceSpec("api", "API", ServiceKind.WEB)).id
        val old = services.create(environment, ServiceSpec("old", "Old", ServiceKind.WORKER)).id
        suspend fun deploy(serviceId: UUID, repository: String, n: Int): Deployment {
            val build = builds.request(serviceId, sha(n), null, "main").also { builds.markRunning(it.id) }
            val token = requireNotNull(tokens.token(BuildJobs.name(build.id), tokens.issue(build.id), listOf("repository:$repository:pull,push")))
            listOf(LAYER, image(n), cache(n)).forEach { upload(repository, token, it) }
            manifest(repository, sha(n), token, "application/vnd.docker.distribution.manifest.v2+json", """{"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json","config":${descriptor("application/vnd.docker.container.image.v1+json", image(n))},"layers":[${descriptor("application/vnd.docker.image.rootfs.diff.tar.gzip", LAYER)}]}""")
            manifest(repository, "cache", token, "application/vnd.oci.image.index.v1+json", """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[${descriptor("application/vnd.oci.image.layer.v1.tar+gzip", LAYER)},${descriptor("application/vnd.buildkit.cacheconfig.v0", cache(n))}]}""")
            return succeed(build.id, "${TestRegistry.ADDRESS}/$repository:${sha(n)}")
        }
        val released = (1..30).map { deploy(api, "acme/store-api", it) }
        deployments.transition(deployments.rollback(released[2].id).id, DeploymentStatus.RUNNING)
        (31..32).forEach { deploy(old, "acme/store-old", it) }
        services.delete(old)
        val server = embeddedServer(Netty, port = 0) {
            liftgate(mockk<App> {
                every { config } returns TestRegistry.config
                every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                every { registryTokens } returns tokens
            })
        }.start()
        val config = TestRegistry.config.copy(internalUrl = "http://localhost:${server.engine.resolvedConnectors().single().port}")
        val http = HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(createClientPlugin("distribution") {
                onRequest { request, _ ->
                    if (request.url.host == TestRegistry.ADDRESS.substringBefore(':')) {
                        request.url.host = TestRegistry.container.host
                        request.url.port = TestRegistry.container.getMappedPort(5000)
                    }
                }
            })
        }

        val deleted = try {
            listOf(janitor(config, http).runOnce(), janitor(config, http).runOnce())
        } finally {
            server.stop()
        }

        assertEquals(listOf(22, 0), deleted)
        val pull = requireNotNull(tokens.token("pull", "pull-password", listOf("repository:acme/store-api:pull", "repository:acme/store-old:pull")))
        assertEquals((21..30).map(::sha).toSet() + sha(3) + "cache", tags("acme/store-api", pull))
        assertEquals(emptySet(), tags("acme/store-old", pull))
        assertEquals(((1..20) - 3).map(::sha).toSet(), pruned())
        assertPruned(released[0])
        assertEquals(0L, orphans())

        val gc = TestRegistry.container.execInContainer("env", "REGISTRY_STORAGE_MAINTENANCE_READONLY={\"enabled\":true}", "registry", "garbage-collect", "--delete-untagged", "/etc/docker/registry/config.yml")
        assertEquals(0, gc.exitCode, gc.stderr)
        listOf(image(3), image(30), cache(30), LAYER).forEach { assertTrue(stored(it), it) }
        listOf(image(1), image(20), cache(29), image(31)).forEach { assertFalse(stored(it), it) }
    }

    private fun sha(n: Int) = "%040x".format(n)

    private fun image(n: Int) = """{"build":$n}"""

    private fun cache(n: Int) = """{"cache":$n}"""

    private fun digest(content: String) = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toByteArray()))

    private fun descriptor(type: String, content: String) = """{"mediaType":"$type","size":${content.length},"digest":"${digest(content)}"}"""

    private fun upload(repository: String, token: String, content: String) {
        val location = TestRegistry.send("POST", "/v2/$repository/blobs/uploads/", token).headers().firstValue("Location").orElseThrow()
        val status = TestRegistry.send("PUT", "$location${if ('?' in location) '&' else '?'}digest=${digest(content)}", token, content, "application/octet-stream").statusCode()
        assertEquals(201, status, content)
    }

    private fun manifest(repository: String, tag: String, token: String, type: String, content: String) {
        val response = TestRegistry.send("PUT", "/v2/$repository/manifests/$tag", token, content, type)
        assertEquals(201, response.statusCode(), response.body())
    }

    private fun stored(content: String) = digest(content).substringAfter(':').let {
        TestRegistry.container.execInContainer("test", "-e", "/var/lib/registry/docker/registry/v2/blobs/sha256/${it.take(2)}/$it/data").exitCode == 0
    }

    private fun tags(repository: String, token: String): Set<String> {
        val response = TestRegistry.send("GET", "/v2/$repository/tags/list", token)
        if (response.statusCode() == 404) return emptySet()
        return json.parseToJsonElement(response.body()).jsonObject["tags"]?.takeIf { it is JsonArray }?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
    }
}
