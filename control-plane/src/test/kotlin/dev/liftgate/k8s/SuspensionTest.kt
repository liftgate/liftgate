package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.TestNats
import dev.liftgate.admin.Admin
import dev.liftgate.build.Builder
import dev.liftgate.build.BuildJobs
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Builds
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.Deployments
import dev.liftgate.domain.Domains
import dev.liftgate.events.OutboxRelay
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.secret.SecretBox
import dev.liftgate.service.EnvVars
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.http.RecordedRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.fabric8.kubernetes.api.model.apps.Deployment as KubeDeployment

/**
 * @author Dean
 * @date 9/27/2026
 */
@EnableKubernetesMockClient
class SuspensionTest {
    lateinit var server: KubernetesMockServer
    lateinit var client: KubernetesClient

    private val db = TestDatabase.clean()
    private val nats = TestNats.clean()
    private val consumers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val builds = Builds(db)
    private val deployments = Deployments(db)
    private val app = mockk<App>().also {
        every { it.config } returns testConfig()
        every { it.nats } returns nats
        every { it.scope } returns consumers
        every { it.services } returns Services(db)
        every { it.deployments } returns deployments
        every { it.builds } returns builds
        every { it.envVars } returns EnvVars(db, SecretBox(ByteArray(32)))
        every { it.domains } returns Domains(db, "liftgate.app")
    }
    private val requests = ConcurrentLinkedQueue<Triple<String, String, String>>()

    private suspend fun seed(): Pair<UUID, List<Service>> {
        val org = Orgs(db).create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id)
        val projects = Projects(db)
        val project = projects.create(org.id, "shop", "Shop", "acme/shop", 42)
        val environment = projects.environments(project.id).single()
        val services = listOf(ServiceSpec("api", "API", ServiceKind.WEB, replicas = 2), ServiceSpec("worker", "Worker", ServiceKind.WORKER, replicas = 3))
            .map { Services(db).create(environment.id, it) }
        Domains(db, "liftgate.app").ensurePlatform(services.first(), project, org)
        services.forEach { deployments.transition(release(it).id, DeploymentStatus.RUNNING, replicasReady = it.replicas) }
        val namespaced = "namespaces/${environment.namespace}"
        val applied = listOf("/api/v1/$namespaced", "/api/v1/$namespaced/resourcequotas/liftgate") +
            listOf("default-deny", "allow-internal", "allow-egress").map { "/apis/networking.k8s.io/v1/$namespaced/networkpolicies/$it" }
        val perService = services.flatMap {
            listOf("/api/v1/$namespaced/secrets/${it.slug}-env", "/apis/apps/v1/$namespaced/deployments/${it.slug}", "/api/v1/$namespaced/services/${it.slug}", routePath(namespaced, it))
        }
        (applied + perService).forEach { path -> server.expect().patch().withPath("$path?fieldManager=liftgate&force=true").andReply(200) { record(it) }.always() }
        services.flatMap { listOf("/api/v1/$namespaced/services/${it.slug}", routePath(namespaced, it), "/apis/batch/v1/$namespaced/cronjobs/${it.slug}") }
            .forEach { path -> server.expect().delete().withPath(path).andReply(200) { record(it).let { StatusBuilder().build() } }.always() }
        return org.id to services
    }

    private suspend fun release(service: Service) = builds.markSucceeded(builds.request(service.id, "abc123", null, "main").id, "registry/acme/shop-${service.slug}:abc123")

    private fun routePath(namespaced: String, service: Service) = "/apis/gateway.networking.k8s.io/v1/$namespaced/httproutes/${service.slug}"

    private fun record(request: RecordedRequest) = request.utf8Body.also { requests += Triple(request.method, request.path, it) }

    private fun sent(method: String, kind: String) = requests.filter { (verb, path) -> verb == method && "/$kind/" in path }.map { (_, path, body) -> path to body }

    private fun workloads() = sent("PATCH", "deployments").map { client.kubernetesSerialization.unmarshal(it.second, KubeDeployment::class.java) }

    private suspend fun admin(vararg args: String) = Admin(db).run(args.toList())

    @Test
    fun `admin suspend stops every deployment of the org and removes its routes within 60 s`() = runBlocking {
        val (orgId, services) = seed()
        Suspension(app, client).start()
        try {
            admin("suspend", "acme", "mining")
            OutboxRelay(db, nats).runOnce()
            withTimeout(60.seconds) { while (sent("DELETE", "cronjobs").size < services.size) delay(100) }
        } finally {
            consumers.cancel()
        }
        val workloads = workloads()
        assertEquals(services.map { it.slug }.toSet(), workloads.map { it.metadata.name }.toSet())
        workloads.forEach {
            assertEquals(0, it.spec.replicas)
            assertEquals(orgId.toString(), it.metadata.labels[ORG_ID_LABEL])
        }
        assertEquals(services.map { it.slug }.toSet(), sent("DELETE", "httproutes").map { it.first.substringAfterLast('/') }.toSet())
        assertTrue(sent("PATCH", "httproutes").isEmpty() && sent("PATCH", "services").isEmpty())
    }

    @Test
    fun `a later release of a suspended org stays stopped and unsuspend restores the configured replicas`() = runBlocking {
        val (orgId, services) = seed()
        val (web, worker) = services
        admin("suspend", "acme", "mining")
        Reconciler(app, client).release(release(web).id)
        assertEquals(listOf(0), workloads().map { it.spec.replicas })
        assertTrue(sent("PATCH", "httproutes").isEmpty())

        requests.clear()
        admin("unsuspend", "acme")
        Suspension(app, client).reapply(orgId)
        assertEquals(mapOf("api" to 2, "worker" to 3), workloads().associate { it.metadata.name to it.spec.replicas })
        assertEquals(listOf("api"), sent("PATCH", "httproutes").map { it.first.substringAfterLast('/').substringBefore('?') })
        assertEquals(listOf(worker.slug), sent("DELETE", "httproutes").map { it.first.substringAfterLast('/') })
    }

    @Test
    fun `a suspended release renders no replicas, a suspended cron job and no routing`() {
        val suspended = testRelease().copy(org = testOrg.copy(suspendedAt = Instant.now()))
        assertEquals(0, Resources.deployment(suspended, null).spec.replicas)
        assertEquals(true, Resources.cronJob(suspended, null).spec.suspend)
        assertFalse(suspended.routable)
        assertEquals(false, Resources.cronJob(testRelease(), null).spec.suspend)
    }

    @Test
    fun `builds of a suspended org are refused`() = runBlocking {
        val queued = testBuild.copy(status = BuildStatus.QUEUED, imageRef = null)
        val builds = mockk<Builds>(relaxUnitFun = true) { coEvery { byId(queued.id) } returns queued }
        val app = mockk<App> {
            every { config } returns testConfig()
            every { this@mockk.builds } returns builds
            every { services } returns mockk<Services> {
                coEvery { scope(testService.id) } returns ServiceScope(testService, testEnvironment, testProject, testOrg.copy(suspendedAt = Instant.now()))
            }
        }
        Builder(app, client).build(queued.id)
        coVerify { builds.markFailed(queued.id, "the organization is suspended") }
        coVerify(exactly = 0) { builds.markRunning(any()) }
        assertNull(client.batch().v1().jobs().inNamespace("liftgate-build").withName(BuildJobs.name(queued.id)).get())
    }
}
