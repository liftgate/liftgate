package dev.liftgate.deploy

import dev.liftgate.db.Db
import dev.liftgate.db.Outbox
import dev.liftgate.events.Subject
import dev.liftgate.http.LiftgateException
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
@Testcontainers(disabledWithoutDocker = true)
class DeploymentsTest {
    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
    }

    @Test
    fun `builds release, a running deployment supersedes older ones and rollback re-releases the older build`() = runBlocking {
        val db = Db(
            testConfig(
                mapOf(
                    "LIFTGATE_DATABASE_URL" to postgres.jdbcUrl,
                    "LIFTGATE_DATABASE_USER" to postgres.username,
                    "LIFTGATE_DATABASE_PASSWORD" to postgres.password,
                ),
            ),
        )
        db.migrate()
        val orgs = Orgs(db)
        val projects = Projects(db)
        val builds = Builds(db)
        val deployments = Deployments(db)
        val org = orgs.create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id)
        val project = projects.create(org.id, "shop", "Shop", "acme/shop", 42)
        val services = Services(db)
        val service = services.create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
        assertEquals(org, services.scope(service.id)?.org)
        suspend fun release(sha: String) = builds.markSucceeded(builds.request(service.id, sha, null, "main").id, "registry/acme/shop-api:$sha")

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
        db.close()
    }
}
