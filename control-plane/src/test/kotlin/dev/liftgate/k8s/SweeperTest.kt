package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.TestNats
import dev.liftgate.admin.Admin
import dev.liftgate.build.BUILD_LABEL
import dev.liftgate.build.BuildJobs
import dev.liftgate.db.Builds as BuildsTable
import dev.liftgate.db.Deployments as DeploymentsTable
import dev.liftgate.db.Outbox
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Builds
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.Deployments
import dev.liftgate.deploy.createDeployment
import dev.liftgate.deploy.toBuild
import dev.liftgate.domain.Domains
import dev.liftgate.events.OutboxRelay
import dev.liftgate.events.Redeliver
import dev.liftgate.events.Subject
import dev.liftgate.events.uuid
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.secret.SecretBox
import dev.liftgate.service.EnvVars
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.testcontainers.DockerClientFactory
import java.net.URLDecoder
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import io.fabric8.kubernetes.api.model.apps.Deployment as KubeDeployment

/**
 * @author Dean
 * @date 9/27/2026
 */
@EnableKubernetesMockClient(crud = true)
class SweeperTest {
    lateinit var server: KubernetesMockServer
    lateinit var client: KubernetesClient

    private val db = TestDatabase.clean()
    private val nats = TestNats.clean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val builds = Builds(db)
    private val metrics = SimpleMeterRegistry()
    private val deployments = Deployments(db, metrics)
    private val app = mockk<App>().also {
        every { it.config } returns testConfig(mapOf("LIFTGATE_LEADER_ELECTION" to "off"))
        every { it.db } returns db
        every { it.nats } returns nats
        every { it.scope } returns scope
        every { it.services } returns Services(db)
        every { it.deployments } returns deployments
        every { it.builds } returns builds
        every { it.envVars } returns EnvVars(db, SecretBox(ByteArray(32)))
        every { it.domains } returns Domains(db, "liftgate.app")
    }
    private val sweeper by lazy { Sweeper(app, client) }

    @AfterTest
    fun stop() = scope.cancel()

    private suspend fun seed(): ServiceScope {
        val org = Orgs(db).create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id)
        val projects = Projects(db)
        val project = projects.create(org.id, "shop", "Shop", "acme/shop", 42)
        val service = Services(db).create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB, replicas = 2))
        return requireNotNull(Services(db).scope(service.id)).also { Domains(db, "liftgate.app").ensurePlatform(it) }
    }

    private suspend fun age(minutes: Long, vararg ids: UUID) = db.tx {
        ids.forEach { id ->
            BuildsTable.update({ BuildsTable.id eq id }) {
                it[createdAt] = now().minusMinutes(minutes)
                it[startedAt] = now().minusMinutes(minutes)
            }
            DeploymentsTable.update({ DeploymentsTable.id eq id }) { it[createdAt] = now().minusMinutes(minutes) }
        }
    }

    private suspend fun ageRequests(published: Boolean) = db.tx {
        Outbox.update {
            it[createdAt] = now().minusMinutes(6)
            if (published) it[publishedAt] = now().minusMinutes(6)
        }
    }

    private suspend fun requests(subject: Subject, key: String) = db.tx {
        Outbox.selectAll().where { Outbox.subject eq subject.value }.map { it[Outbox.payload].getValue(key).jsonPrimitive.content }
    }.groupingBy { it }.eachCount()

    @Test
    fun `one sweep leaves no workload of a namespace without an environment or of a service deleted mid-release`() = runBlocking {
        client.resource(Resources.namespace(testRelease())).create()
        sweeper.removeOrphans()
        assertEquals(listOf(testEnvironment.namespace), client.namespaces().list().items.map { it.metadata.name })

        val kept = seed()
        val gone = Services(db).create(kept.environment.id, ServiceSpec("gone", "Gone", ServiceKind.WEB))
        val releases = listOf(kept.service, gone).map { testRelease(it).copy(environment = kept.environment, org = kept.org) } + testRelease()
        client.resource(Resources.namespace(releases.first())).create()
        client.resource(NamespaceBuilder().withNewMetadata().withName("liftgate-build").addToLabels(MANAGED_LABEL, "true").endMetadata().build()).create()
        releases.flatMap { listOf(Resources.secret(it), Resources.service(it), Resources.httpRoute(it, "liftgate-system", "liftgate"), Resources.deployment(it, null), Resources.cronJob(it, null)) }
            .forEach { client.resource(it).create() }
        Services(db).delete(gone.id)
        server.expect().get().withPath("/api/v1/secrets?labelSelector=liftgate.dev%2Fmanaged%3Dtrue").andReturn(403, StatusBuilder().build()).always()

        sweeper.removeOrphans()

        assertEquals(setOf(kept.environment.namespace, "liftgate-build"), client.namespaces().list().items.map { it.metadata.name }.toSet())
        fun labelled(id: UUID) = listOf(client.apps().deployments(), client.batch().v1().cronjobs(), client.services(), client.secrets(), client.resources(HTTPRoute::class.java))
            .flatMap { it.inNamespace(kept.environment.namespace).withLabel(SERVICE_ID_LABEL, id.toString()).list().items }
        assertEquals(5, labelled(kept.service.id).size)
        assertTrue(labelled(gone.id).isEmpty())
    }

    @Test
    fun `a queued build with no outbox row is requested again and reaches the builder`() = runBlocking {
        val service = seed().service
        val stranded = db.tx {
            BuildsTable.insertReturning {
                it[id] = UUID.randomUUID()
                it[serviceId] = service.id
                it[commitSha] = "abc123"
                it[branch] = "main"
                it[status] = BuildStatus.QUEUED.sql
                it[createdAt] = now().minusMinutes(6)
            }.single().toBuild()
        }
        val received = Channel<String>(Channel.UNLIMITED)
        nats.consume(Subject.BUILD_REQUESTED, "builder-build-requested", scope) { received.send(it.getValue("buildId").jsonPrimitive.content) }
        sweeper.redrive()
        OutboxRelay(db, nats).runOnce()
        assertEquals(stranded.id.toString(), withTimeout(10.seconds) { received.receive() })
        sweeper.redrive()
        assertEquals(mapOf(stranded.id.toString() to 1), requests(Subject.BUILD_REQUESTED, "buildId"))
    }

    @Test
    fun `stuck builds and releases are requested again once and time out after 45 minutes`() = runBlocking {
        val service = seed().service.id
        val (stale, fresh, jobless, working, expired) = listOf("preview", "main", "staging", "canary", "old").map { builds.request(service, "abc123", null, it).id }
        listOf(jobless, working).forEach { builds.markRunning(it) }
        client.resource(JobBuilder().withNewMetadata().withName(BuildJobs.name(working)).withNamespace("liftgate-build").addToLabels(BUILD_LABEL, working.toString()).endMetadata().build()).create()
        val (pending, releasing) = db.tx { List(2) { createDeployment(service, working).id } }
        deployments.transition(releasing, DeploymentStatus.RELEASING)
        age(6, stale, pending)
        age(3, jobless, working)
        age(46, expired, releasing)
        ageRequests(published = true)

        repeat(2) { sweeper.redrive() }

        val once = listOf(fresh, working, expired).associate { it.toString() to 1 }
        assertEquals(once + listOf(stale, jobless).associate { it.toString() to 2 }, requests(Subject.BUILD_REQUESTED, "buildId"))
        assertEquals(mapOf(pending.toString() to 2, releasing.toString() to 1), requests(Subject.RELEASE_REQUESTED, "deploymentId"))
        assertEquals(BuildStatus.FAILED to "timed out", builds.byId(expired)?.let { it.status to it.error })
        assertEquals(DeploymentStatus.FAILED to "timed out", deployments.byId(releasing)?.let { it.status to it.error })
        assertEquals(1L, metrics.timer("liftgate.release.duration", "status", "failed").count())
        assertEquals(BuildStatus.QUEUED, builds.byId(stale)?.status)
    }

    @Test
    fun `queued builds are neither requested again nor timed out while the builder holds their requests, and running builds still time out`() = runBlocking {
        val service = seed().service.id
        val (build, stuck) = listOf("main", "preview").map { builds.request(service, "abc123", null, it).id }
        builds.markRunning(stuck)
        age(46, build, stuck)
        ageRequests(published = false)
        val held = Channel<Unit>(Channel.UNLIMITED)
        nats.consume(Subject.BUILD_REQUESTED, "builder-build-requested", scope) { held.send(Unit); throw Redeliver(Duration.ofMinutes(1)) }
        OutboxRelay(db, nats).runOnce()
        withTimeout(10.seconds) { held.receive() }
        sweeper.redrive()
        assertEquals(listOf(build, stuck).associate { it.toString() to 1 }, requests(Subject.BUILD_REQUESTED, "buildId"))
        assertEquals(listOf(BuildStatus.QUEUED, BuildStatus.FAILED), listOf(build, stuck).map { builds.byId(it)?.status })
    }

    @Test
    fun `a build or release request the relay has not published yet is not repeated however old it is`() = runBlocking {
        val service = seed().service.id
        val build = builds.request(service, "abc123", null, "main").id
        val deployment = db.tx { createDeployment(service, build).id }
        age(6, build, deployment)
        ageRequests(published = false)
        sweeper.redrive()
        assertEquals(mapOf(build.toString() to 1), requests(Subject.BUILD_REQUESTED, "buildId"))
        assertEquals(mapOf(deployment.toString() to 1), requests(Subject.RELEASE_REQUESTED, "deploymentId"))
    }

    @Test
    fun `a resync rebuilds a lost workload, keeps unreleased settings off a live one, re-applies its namespace objects and keeps a suspended org stopped`() = runBlocking {
        val service = seed()
        val deployment = assertNotNull(builds.markSucceeded(builds.request(service.service.id, "abc123", null, "main").id, "registry/acme/shop-api:abc123"))
        deployments.transition(deployment.id, DeploymentStatus.RUNNING, replicasReady = 2)
        val namespaced = "namespaces/${service.environment.namespace}"
        val applied = ConcurrentLinkedQueue<Pair<String, String>>()
        val environment = listOf("/api/v1/$namespaced", "/api/v1/$namespaced/resourcequotas/liftgate") +
            listOf("default-deny", "allow-internal", "allow-egress").map { "/apis/networking.k8s.io/v1/$namespaced/networkpolicies/$it" }
        val secret = "/api/v1/$namespaced/secrets/api-env-${deployment.id.toString().take(8)}"
        val paths = environment + listOf(secret, "/apis/apps/v1/$namespaced/deployments/api") +
            listOf("/api/v1/$namespaced/services/api", "/apis/gateway.networking.k8s.io/v1/$namespaced/httproutes/api")
        paths.forEach { path ->
            server.expect().patch().withPath("$path?fieldManager=liftgate&force=true").andReply(200) { request -> request.utf8Body.also { applied += path to it } }.always()
        }
        fun workload() = client.kubernetesSerialization.unmarshal(applied.single { it.first.endsWith("/deployments/api") }.second, KubeDeployment::class.java)

        sweeper.resync()
        val egress = applied.single { it.first.endsWith("/allow-egress") }.second
        val running = workload()
        assertEquals(2, running.spec.replicas)
        client.resource(running).create()
        applied.clear()
        Services(db).update(service.service.id, service.service.spec().copy(port = 3000))
        sweeper.resync()
        assertEquals(environment.toSet(), applied.map { it.first }.toSet())
        assertEquals(egress, applied.single { it.first.endsWith("/allow-egress") }.second)

        Admin(db).run(listOf("suspend", "acme", "mining"))
        sweeper.resync()
        val stopped = workload()
        assertEquals(0, stopped.spec.replicas)
        assertTrue(listOf("/api/v1/$namespaced", secret).all { path -> applied.any { it.first == path } })
        assertTrue(applied.none { "/httproutes/" in it.first || "/services/" in it.first })

        applied.clear()
        client.resource(running).delete()
        client.resource(stopped).create()
        sweeper.resync()
        assertEquals(environment.toSet(), applied.map { it.first }.toSet())
        assertEquals(0, client.apps().deployments().inNamespace(service.environment.namespace).withName("api").get().spec.replicas)
    }

    @Test
    fun `env secret pruning keeps the secrets of live deployments and the newest five of a service`() = runBlocking {
        val scope = seed()
        suspend fun release(serviceId: UUID, sha: String) = assertNotNull(builds.markSucceeded(builds.request(serviceId, sha, null, "main").id, "registry/acme/shop:$sha"))
        release(Services(db).create(scope.environment.id, ServiceSpec("small", "Small", ServiceKind.WORKER)).id, "abc123")
        val released = (1..8).map { release(scope.service.id, "sha$it") }
        deployments.transition(released[1].id, DeploymentStatus.RUNNING)
        released.drop(2).forEach { deployments.transition(it.id, DeploymentStatus.FAILED) }

        sweeper.pruneSecrets()

        val deletes = List(server.requestCount) { server.takeRequest() }.filter { it.method == "DELETE" }
        val selector = URLDecoder.decode(deletes.single().path.substringAfter("/api/v1/namespaces/${scope.environment.namespace}/secrets?labelSelector="), Charsets.UTF_8)
        val requirements = selector.split(Regex(",(?![^(]*\\))")).toSet()
        val kept = requirements.single { " notin " in it }.substringAfter("(").removeSuffix(")").split(",").toSet()
        assertEquals(setOf("$SERVICE_ID_LABEL=${scope.service.id}", DEPLOYMENT_LABEL), requirements.filterNot { " notin " in it }.toSet())
        assertEquals((listOf(released[1]) + released.takeLast(5)).map { it.id.toString() }.toSet(), kept)
    }

    @Test
    fun `a build queued before a three minute postgres outage still finishes within six minutes`() = runBlocking {
        val build = builds.request(seed().service.id, "abc123", null, "main").id
        val docker = DockerClientFactory.instance().client()
        docker.pauseContainerCmd(TestDatabase.postgres.containerId).exec()
        try {
            OutboxRelay(db, nats).start(scope)
            nats.consume(Subject.BUILD_REQUESTED, "builder-build-requested", scope) {
                builds.markRunning(it.uuid("buildId"))
                builds.markSucceeded(it.uuid("buildId"), "registry/acme/shop-api:abc123")
            }
            sweeper.start()
            delay(3.minutes)
        } finally {
            docker.unpauseContainerCmd(TestDatabase.postgres.containerId).exec()
        }
        withTimeout(3.minutes) { while (builds.byId(build)?.status in setOf(BuildStatus.QUEUED, BuildStatus.RUNNING)) delay(1000) }
        assertEquals(BuildStatus.SUCCEEDED, builds.byId(build)?.status)
    }
}
