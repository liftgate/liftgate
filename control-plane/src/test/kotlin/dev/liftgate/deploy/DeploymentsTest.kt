package dev.liftgate.deploy

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.db.Outbox
import dev.liftgate.domain.Domains
import dev.liftgate.events.Subject
import dev.liftgate.events.changed
import dev.liftgate.http.LiftgateException
import dev.liftgate.k8s.Reconciler
import dev.liftgate.org.Limits
import dev.liftgate.org.Orgs
import dev.liftgate.org.Plan
import dev.liftgate.org.Plans
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.secret.SecretBox
import dev.liftgate.service.EnvVar
import dev.liftgate.service.EnvVars
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.ktor.http.HttpStatusCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.fabric8.kubernetes.api.model.apps.Deployment as KubeDeployment

/**
 * @author Dean
 * @date 9/17/2026
 */
class DeploymentStatusTest {
    @Test
    fun `transitions follow the release lifecycle`() {
        assertTrue(DeploymentStatus.PENDING.allows(DeploymentStatus.RELEASING))
        assertTrue(DeploymentStatus.RELEASING.allows(DeploymentStatus.RELEASING))
        assertTrue(DeploymentStatus.RELEASING.allows(DeploymentStatus.RUNNING))
        assertTrue(DeploymentStatus.RELEASING.allows(DeploymentStatus.FAILED))
        assertTrue(DeploymentStatus.RUNNING.allows(DeploymentStatus.SUPERSEDED))
        assertFalse(DeploymentStatus.RUNNING.allows(DeploymentStatus.ROLLED_BACK))
        assertTrue(DeploymentStatus.FAILED.allows(DeploymentStatus.RUNNING))
        assertFalse(DeploymentStatus.RUNNING.allows(DeploymentStatus.PENDING))
        assertFalse(DeploymentStatus.RUNNING.allows(DeploymentStatus.RELEASING))
        assertFalse(DeploymentStatus.SUPERSEDED.allows(DeploymentStatus.RUNNING))
        assertFalse(DeploymentStatus.ROLLED_BACK.allows(DeploymentStatus.RELEASING))
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
@EnableKubernetesMockClient
class DeploymentsTest {
    lateinit var server: KubernetesMockServer
    lateinit var client: KubernetesClient

    private val db = TestDatabase.clean()
    private val builds = Builds(db)
    private val deployments = Deployments(db)
    private val services = Services(db)
    private val envVars = EnvVars(db, SecretBox(ByteArray(32)))
    private val app = mockk<App> {
        every { config } returns testConfig()
        every { this@mockk.builds } returns this@DeploymentsTest.builds
        every { this@mockk.deployments } returns this@DeploymentsTest.deployments
        every { this@mockk.services } returns this@DeploymentsTest.services
        every { this@mockk.envVars } returns this@DeploymentsTest.envVars
        every { domains } returns Domains(this@DeploymentsTest.db, "liftgate.app")
    }

    @Test
    fun `builds release, a running deployment supersedes older ones and a rollback replaces the running one once it runs`() = runBlocking {
        val service = seed()
        assertEquals("acme", services.scope(service.id)?.org?.slug)

        val first = release(service, "aaa")
        deployments.transition(first.id, DeploymentStatus.RELEASING)
        deployments.transition(first.id, DeploymentStatus.RUNNING, replicasReady = 1)
        val second = release(service, "bbb")
        deployments.transition(second.id, DeploymentStatus.RUNNING, replicasReady = 1)
        assertEquals(DeploymentStatus.SUPERSEDED, deployments.byId(first.id)?.status)
        assertEquals(second.id, deployments.current(service.id)?.id)

        val third = deployments.rollback(first.id)
        assertEquals(first.buildId, third.buildId)
        assertEquals(DeploymentStatus.PENDING, third.status)
        deployments.transition(third.id, DeploymentStatus.RELEASING)
        assertEquals(second.id, deployments.current(service.id)?.id)
        deployments.transition(third.id, DeploymentStatus.RUNNING, replicasReady = 1)
        assertEquals(DeploymentStatus.SUPERSEDED, deployments.byId(second.id)?.status)
        assertEquals(third.id, deployments.current(service.id)?.id)
        assertEquals(HttpStatusCode.Conflict, assertFailsWith<LiftgateException> { deployments.rollback(third.id) }.status)
        assertEquals(3L, db.tx { Outbox.selectAll().where { Outbox.subject eq Subject.RELEASE_REQUESTED.value }.count() })
    }

    @Test
    fun `a release is timed from its creation until its rollout runs or fails`() = runBlocking {
        val db = TestDatabase.clean()
        val metrics = SimpleMeterRegistry()
        val deployments = Deployments(db, metrics)
        val projects = Projects(db)
        val project = projects.create(Orgs(db).create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id).id, "shop", "Shop", "acme/shop", 42)
        val service = Services(db).create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
        val builds = Builds(db)
        suspend fun release(sha: String) = assertNotNull(builds.markSucceeded(builds.request(service.id, sha, null, "main").id, "registry/acme/shop-api:$sha"))
        fun released(status: String) = metrics.timer("liftgate.release.duration", "status", status).count()

        val web = release("aaa")
        deployments.transition(web.id, DeploymentStatus.RELEASING)
        deployments.transition(web.id, DeploymentStatus.RELEASING, replicasReady = 1)
        coroutineScope { repeat(2) { launch(Dispatchers.IO) { deployments.transition(web.id, DeploymentStatus.RUNNING, replicasReady = 2) } } }
        deployments.transition(web.id, DeploymentStatus.RUNNING, replicasReady = 1)
        deployments.transition(web.id, DeploymentStatus.FAILED, error = "crash loop")
        val stalled = release("bbb")
        deployments.transition(stalled.id, DeploymentStatus.RELEASING)
        deployments.transition(stalled.id, DeploymentStatus.FAILED, error = "api has timed out progressing")

        assertEquals(1L, released("running"))
        assertEquals(1L, released("failed"))
    }

    @Test
    fun `an update that only changes the ready replicas is published as unchanged`() = runBlocking {
        val db = TestDatabase.clean()
        val deployments = Deployments(db)
        val projects = Projects(db)
        val project = projects.create(Orgs(db).create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id).id, "shop", "Shop", "acme/shop", 42)
        val service = Services(db).create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
        val builds = Builds(db)
        val deployment = assertNotNull(builds.markSucceeded(builds.request(service.id, "aaa", null, "main").id, "registry/acme/shop-api:aaa"))
        deployments.transition(deployment.id, DeploymentStatus.RUNNING, replicasReady = 1)
        deployments.transition(deployment.id, DeploymentStatus.RUNNING, replicasReady = 2)
        deployments.transition(deployment.id, DeploymentStatus.FAILED, error = "crash loop")
        val updates = db.tx { Outbox.selectAll().where { Outbox.subject eq Subject.DEPLOYMENT_UPDATED.value }.orderBy(Outbox.id).map { it[Outbox.payload] } }
        assertEquals(listOf("running" to true, "running" to false, "failed" to true), updates.map { it.getValue("status").jsonPrimitive.content to it.changed })
    }

    @Test
    fun `a pending or releasing deployment overtaken by a newer running one is published as superseded`() = runBlocking {
        val db = TestDatabase.clean()
        val deployments = Deployments(db)
        val projects = Projects(db)
        val project = projects.create(Orgs(db).create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id).id, "shop", "Shop", "acme/shop", 42)
        val service = Services(db).create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
        val builds = Builds(db)
        suspend fun release(sha: String) = assertNotNull(builds.markSucceeded(builds.request(service.id, sha, null, "main").id, "registry/acme/shop-api:$sha"))
        val releasing = release("aaa")
        deployments.transition(releasing.id, DeploymentStatus.RELEASING)
        val pending = release("bbb")
        val newest = release("ccc")
        deployments.transition(newest.id, DeploymentStatus.RUNNING, replicasReady = 1)
        val published = db.tx { Outbox.selectAll().where { Outbox.subject eq Subject.DEPLOYMENT_UPDATED.value }.map { it[Outbox.payload] } }
            .map { it.getValue("deploymentId").jsonPrimitive.content to it.getValue("status").jsonPrimitive.content }
        val expected = listOf("${releasing.id}" to "releasing", "${releasing.id}" to "superseded", "${pending.id}" to "superseded", "${newest.id}" to "running")
        assertEquals(expected.toSet(), published.toSet())
        assertEquals(expected.size, published.size)
    }

    private suspend fun seed(): Service {
        val projects = Projects(db)
        val project = projects.create(Orgs(db).create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id).id, "shop", "Shop", "acme/shop", 42)
        return services.create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
    }

    private suspend fun release(service: Service, sha: String) =
        assertNotNull(builds.markSucceeded(builds.request(service.id, sha, null, "main").id, "registry/acme/shop-api:$sha"))

    private suspend fun running(service: Service, sha: String) = release(service, sha).also { deployments.transition(it.id, DeploymentStatus.RUNNING, replicasReady = 1) }

    private suspend fun conflict(block: suspend () -> Unit) {
        val error = assertFailsWith<LiftgateException> { block() }
        assertEquals(HttpStatusCode.Conflict, error.status)
    }

    @Test
    fun `an env change followed by redeploy creates a pending deployment of the running build with the new sealed env and no build`() = runBlocking {
        val service = seed()
        envVars.replace(service.id, listOf(EnvVar("GREETING", "hello")))
        val first = running(service, "aaa")
        envVars.replace(service.id, listOf(EnvVar("GREETING", "bye", secret = true)))
        val built = builds.forService(service.id)

        val redeployed = deployments.redeploy(service.id)

        assertEquals(DeploymentStatus.PENDING to first.buildId, redeployed.status to redeployed.buildId)
        assertEquals(built, builds.forService(service.id))
        val sealed = assertNotNull(redeployed.env)
        assertTrue(sealed.values.none { "bye" in String(Base64.getDecoder().decode(it)) })
        assertEquals(listOf(EnvVar("GREETING", "bye")), envVars.open(sealed))
        assertEquals(listOf(EnvVar("GREETING", "hello")), envVars.open(assertNotNull(deployments.byId(first.id)?.env)))
        assertEquals(service.spec(), redeployed.config)
        assertEquals(first.id, deployments.current(service.id)?.id)
    }

    @Test
    fun `redeploy with nothing running is a conflict`() = runBlocking {
        val service = seed()
        conflict { deployments.redeploy(service.id) }
        deployments.transition(release(service, "aaa").id, DeploymentStatus.FAILED)
        conflict { deployments.redeploy(service.id) }
    }

    @Test
    fun `rolling back to a deployment renders an immutable secret with its env and a workload with its config`() = runBlocking {
        val service = seed()
        envVars.replace(service.id, listOf(EnvVar("GREETING", "v1")))
        val first = running(service, "aaa")
        envVars.replace(service.id, listOf(EnvVar("GREETING", "v2")))
        services.update(service.id, service.spec().copy(port = 3000))
        deployments.transition(deployments.redeploy(service.id).id, DeploymentStatus.RUNNING)
        val rollback = deployments.rollback(first.id)
        val namespaced = "namespaces/${assertNotNull(services.scope(service.id)).environment.namespace}"
        val secretPath = "/api/v1/$namespaced/secrets/api-env-${rollback.id.toString().take(8)}"
        val deploymentPath = "/apis/apps/v1/$namespaced/deployments/api"
        val applied = ConcurrentHashMap<String, String>()
        (listOf("/api/v1/$namespaced", "/api/v1/$namespaced/resourcequotas/liftgate", secretPath, deploymentPath) +
            listOf("default-deny", "allow-internal", "allow-egress").map { "/apis/networking.k8s.io/v1/$namespaced/networkpolicies/$it" })
            .forEach { path -> server.expect().patch().withPath("$path?fieldManager=liftgate&force=true").andReply(200) { it.utf8Body.also { body -> applied[path] = body } }.always() }

        Reconciler(app, client).release(rollback.id)

        val secret = client.kubernetesSerialization.unmarshal(applied.getValue(secretPath), Secret::class.java)
        assertEquals(mapOf("GREETING" to "v1"), secret.data.mapValues { String(Base64.getDecoder().decode(it.value)) })
        assertEquals(true, secret.immutable)
        val container = client.kubernetesSerialization.unmarshal(applied.getValue(deploymentPath), KubeDeployment::class.java).spec.template.spec.containers.single()
        assertEquals(secret.metadata.name, container.envFrom.single().secretRef.name)
        assertEquals(listOf(8080), container.ports.map { it.containerPort })
        assertEquals(DeploymentStatus.RELEASING, deployments.byId(rollback.id)?.status)
    }

    @Test
    fun `a rollback whose snapshot no longer fits the plan is refused`() = runBlocking {
        val limits = Limits(Plans(mapOf("small" to Plan(replicas = 2)), "small"))
        val limited = Services(db, limits)
        val service = seed()
        limited.update(service.id, service.spec().copy(replicas = 2))
        val first = running(service, "aaa")
        limited.update(service.id, service.spec().copy(replicas = 1))
        limited.create(service.environmentId, ServiceSpec("worker", "Worker", ServiceKind.WORKER))
        running(service, "bbb")

        val error = assertFailsWith<LiftgateException> { Deployments(db, limits = limits).rollback(first.id) }

        assertEquals(HttpStatusCode.Conflict to "plan_limit", error.status to error.code)
    }

    @Test
    fun `only the newest deployment falls back to the one still running, which cannot fail while it is being replaced`() = runBlocking {
        val service = seed()
        val first = running(service, "aaa")
        val second = release(service, "bbb")
        assertEquals(first.id, deployments.fallback(second.id)?.id)
        assertNull(deployments.fallback(first.id))
        conflict { deployments.transition(first.id, DeploymentStatus.FAILED, error = "stale progress deadline") }
        release(service, "ccc")
        assertNull(deployments.fallback(second.id))
    }
}
