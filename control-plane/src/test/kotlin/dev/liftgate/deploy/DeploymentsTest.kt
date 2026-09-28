package dev.liftgate.deploy

import dev.liftgate.TestDatabase
import dev.liftgate.db.Outbox
import dev.liftgate.events.Subject
import dev.liftgate.events.changed
import dev.liftgate.http.LiftgateException
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        assertTrue(DeploymentStatus.RUNNING.allows(DeploymentStatus.ROLLED_BACK))
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
class DeploymentsTest {
    @Test
    fun `builds release, a running deployment supersedes older ones and rollback re-releases the older build`() = runBlocking {
        val db = TestDatabase.clean()
        val orgs = Orgs(db)
        val projects = Projects(db)
        val builds = Builds(db)
        val deployments = Deployments(db)
        val org = orgs.create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id)
        val project = projects.create(org.id, "shop", "Shop", "acme/shop", 42)
        val services = Services(db)
        val service = services.create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
        assertEquals(org, services.scope(service.id)?.org)
        suspend fun release(sha: String) = assertNotNull(builds.markSucceeded(builds.request(service.id, sha, null, "main").id, "registry/acme/shop-api:$sha"))

        val first = release("aaa")
        deployments.transition(first.id, DeploymentStatus.RELEASING)
        deployments.transition(first.id, DeploymentStatus.RUNNING, replicasReady = 1)
        val second = release("bbb")
        deployments.transition(second.id, DeploymentStatus.RUNNING, replicasReady = 1)
        assertEquals(DeploymentStatus.SUPERSEDED, deployments.byId(first.id)?.status)
        assertEquals(second.id, deployments.current(service.id)?.id)

        val third = deployments.rollback(first.id)
        assertEquals(first.buildId, third.buildId)
        assertEquals(DeploymentStatus.PENDING, third.status)
        assertEquals(DeploymentStatus.ROLLED_BACK, deployments.byId(second.id)?.status)
        assertFailsWith<LiftgateException> { deployments.transition(second.id, DeploymentStatus.RUNNING) }
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
}
