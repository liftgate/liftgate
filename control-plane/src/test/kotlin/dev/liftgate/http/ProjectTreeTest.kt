package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.auth.Access
import dev.liftgate.auth.ApiTokens
import dev.liftgate.auth.Sessions
import dev.liftgate.db.Domains as DomainsTable
import dev.liftgate.db.now
import dev.liftgate.deploy.Builds
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.Deployments
import dev.liftgate.domain.Domains
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.Projects
import dev.liftgate.service.ProjectTree
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * @author Dean
 * @date 9/27/2026
 */
class ProjectTreeTest {
    private val db = TestDatabase.clean()
    private val orgs = Orgs(db)
    private val projects = Projects(db)
    private val services = Services(db)
    private val member = runBlocking { db.tx { insertUser("dean", null, null, null) } }
    private val outsider = runBlocking { db.tx { insertUser("eve", null, null, null) } }
    private val apiTokens = mockk<ApiTokens>()
    private val app = mockk<App>().also {
        every { it.services } returns services
        every { it.orgs } returns orgs
        every { it.access } returns Access(orgs)
        every { it.apiTokens } returns apiTokens
        every { it.sessions } returns mockk<Sessions> {
            coEvery { resolve("member") } returns member
            coEvery { resolve("outsider") } returns outsider
        }
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.config } returns testConfig()
    }

    @Test
    fun `one request returns the project, its environments and every service, for members only`() = testApplication {
        val acme = orgs.create("acme", "Acme", member.id)
        val other = orgs.create("other", "Other", member.id)
        val shop = projects.create(acme.id, "shop", "Shop", "acme/shop", 42)
        val environments = listOf(projects.environments(shop.id).single(), projects.createEnvironment(shop.id, "staging", "Staging", EnvironmentKind.PREVIEW, "develop"))
        val workers = environments.map { services.create(it.id, ServiceSpec("worker", "Worker", ServiceKind.WORKER, watchPaths = listOf("apps/worker/**", "packages/**"))) }
        coEvery { apiTokens.resolve("lg_acme") } returns (acme.id to member.id)
        coEvery { apiTokens.resolve("lg_other") } returns (other.id to member.id)
        application { liftgate(app) }

        val response = client.get("/api/v1/orgs/acme/projects/shop/tree") { cookie(SESSION_COOKIE, "member") }
        assertEquals(HttpStatusCode.OK, response.status)
        val tree = json.decodeFromString(ProjectTree.serializer(), response.bodyAsText())
        assertEquals(shop to environments, tree.project to tree.environments)
        assertEquals(workers.toSet(), tree.services.toSet())
        assertEquals(listOf("apps/worker/**", "packages/**"), tree.services.first().watchPaths)

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/orgs/acme/projects/shop/tree") { bearerAuth("lg_acme") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/orgs/acme/projects/shop/tree") { bearerAuth("lg_other") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/orgs/acme/projects/shop/tree") { cookie(SESSION_COOKIE, "outsider") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/orgs/acme/projects/missing/tree") { cookie(SESSION_COOKIE, "member") }.status)
    }

    @Test
    fun `services that listen on a port carry their private address`() = runBlocking {
        val shop = projects.create(orgs.create("acme", "Acme", member.id).id, "shop", "Shop", "acme/shop", 42)
        val environment = projects.environments(shop.id).single()
        val created = listOf(
            services.create(environment.id, ServiceSpec("web", "Web", ServiceKind.WEB)),
            services.create(environment.id, ServiceSpec("cache", "Cache", ServiceKind.WORKER, port = 6379)),
            services.create(environment.id, ServiceSpec("jobs", "Jobs", ServiceKind.WORKER)),
        )
        val hosts = mapOf("web" to "web.${environment.namespace}.svc.cluster.local", "cache" to "cache.${environment.namespace}.svc.cluster.local", "jobs" to null)
        assertEquals(hosts, created.associate { it.slug to it.internalHost })
        assertEquals(hosts, services.forEnvironment(environment.id).associate { it.slug to it.internalHost })
        assertEquals(hosts, services.tree("acme", "shop", member.id)?.services?.associate { it.slug to it.internalHost })
        assertEquals(hosts.getValue("cache"), services.update(created[1].id, created[1].spec().copy(replicas = 2)).internalHost)
    }

    @Test
    fun `services carry the url they answer on and the deployment that serves them`() = runBlocking {
        val shop = projects.create(orgs.create("acme", "Acme", member.id).id, "shop", "Shop", "acme/shop", 42)
        val environment = projects.environments(shop.id).single()
        val web = services.create(environment.id, ServiceSpec("web", "Web", ServiceKind.WEB))
        services.create(environment.id, ServiceSpec("jobs", "Jobs", ServiceKind.WORKER))
        val domains = Domains(db, "liftgate.app")
        domains.ensurePlatform(assertNotNull(services.scope(web.id)))
        domains.addCustom(web.id, "shop.acme.dev")
        db.tx { DomainsTable.update({ DomainsTable.hostname eq "shop.acme.dev" }) { it[verifiedAt] = now() } }
        val builds = Builds(db)
        val deployments = Deployments(db)
        suspend fun release(sha: Char) = assertNotNull(builds.markSucceeded(builds.request(web.id, "$sha".repeat(40), null, "main").id, "registry/acme/shop-web:$sha"))
        suspend fun tree() = assertNotNull(services.tree("acme", "shop", member.id)).services.associateBy { it.slug }

        assertEquals(mapOf("jobs" to (null to null), "web" to ("https://web-shop-acme.liftgate.app" to null)), tree().mapValues { (_, service) -> service.url to service.current })
        val first = release('a')
        assertEquals(Service.Current(first.id, DeploymentStatus.PENDING, 0, "a".repeat(40), first.createdAt), tree().getValue("web").current)
        deployments.transition(first.id, DeploymentStatus.RUNNING, replicasReady = 1)
        val second = release('b')
        deployments.transition(second.id, DeploymentStatus.FAILED, error = "crash loop")
        assertEquals(Service.Current(first.id, DeploymentStatus.RUNNING, 1, "a".repeat(40), first.createdAt), tree().getValue("web").current)
        assertEquals(tree().values.toList(), services.withStatus(services.forEnvironment(environment.id)))
    }
}
