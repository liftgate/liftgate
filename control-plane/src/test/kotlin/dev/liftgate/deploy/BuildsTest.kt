package dev.liftgate.deploy

import dev.liftgate.TestDatabase
import dev.liftgate.db.Outbox
import dev.liftgate.events.Subject
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * @author Dean
 * @date 9/27/2026
 */
class BuildsTest {
    private val db = TestDatabase.clean()
    private val builds = Builds(db)
    private val deployments = Deployments(db)

    private suspend fun service(): UUID {
        val org = Orgs(db).create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id)
        val projects = Projects(db)
        val project = projects.create(org.id, "shop", "Shop", "acme/shop", 42)
        return Services(db).create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB)).id
    }

    private suspend fun succeed(build: Build) = builds.markSucceeded(build.id, "registry/acme/shop-api:${build.commitSha}")

    private suspend fun releases() = db.tx { Outbox.selectAll().where { Outbox.subject eq Subject.RELEASE_REQUESTED.value }.count() }

    @Test
    fun `a build that finishes after a newer one succeeded creates no deployment and the service stays on the newer`() = runBlocking {
        val service = service()
        val queued = builds.request(service, "aaa", null, "main")
        val newer = builds.request(service, "bbb", null, "main")
        assertNotNull(succeed(newer))
        assertNull(succeed(queued))

        val running = builds.request(service, "ccc", null, "main").also { builds.markRunning(it.id) }
        val newest = builds.request(service, "ddd", null, "main")
        val released = assertNotNull(succeed(newest))
        assertNull(succeed(running))
        assertEquals(BuildStatus.SUCCEEDED, builds.byId(running.id)?.status)
        assertEquals(listOf(newest.id, newer.id), deployments.forService(service).map { it.buildId })
        assertEquals(released.id, deployments.forService(service, limit = 1).single().id)
        assertEquals(2L, releases())
    }

    @Test
    fun `a redelivered success creates no second deployment`() = runBlocking {
        val build = builds.request(service(), "aaa", null, "main")
        val first = assertNotNull(succeed(build))
        assertNull(succeed(build))
        assertEquals(listOf(first), deployments.forService(build.serviceId))
        assertEquals(1L, releases())
    }

    @Test
    fun `a new request cancels the queued build of its branch`() = runBlocking {
        val service = service()
        val first = builds.request(service, "aaa", null, "main")
        val running = builds.request(service, "bbb", null, "main").also { builds.markRunning(it.id) }
        val preview = builds.request(service, "ccc", null, "preview")
        val latest = builds.request(service, "ddd", null, "main")
        assertNull(succeed(first))
        assertEquals(
            listOf(BuildStatus.CANCELLED, BuildStatus.RUNNING, BuildStatus.QUEUED, BuildStatus.QUEUED),
            listOf(first, running, preview, latest).map { builds.byId(it.id)?.status },
        )
        assertNotNull(builds.byId(first.id)?.finishedAt)
        assertEquals(0L, releases())
    }
}
