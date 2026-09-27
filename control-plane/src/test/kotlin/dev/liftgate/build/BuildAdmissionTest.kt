package dev.liftgate.build

import dev.liftgate.TestDatabase
import dev.liftgate.TestNats
import dev.liftgate.db.Builds as BuildsTable
import dev.liftgate.db.Organizations
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.deploy.Build
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Builds
import dev.liftgate.events.Redeliver
import dev.liftgate.events.Subject
import dev.liftgate.events.uuid
import dev.liftgate.org.Orgs
import dev.liftgate.org.Plan
import dev.liftgate.org.Plans
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * @author Dean
 * @date 9/27/2026
 */
class BuildAdmissionTest {
    private val db = TestDatabase.clean()
    private val admission = admission(Plan(concurrentBuilds = 1, buildsPerHour = 10))
    private val builds = Builds(db)
    private var installation = 0L

    private suspend fun service(org: String): Pair<UUID, UUID> {
        val orgId = Orgs(db).create(org, org, db.tx { insertUser(org, null, null, null) }.id).id
        val projects = Projects(db)
        val project = projects.create(orgId, "shop", "Shop", "$org/shop", ++installation)
        return orgId to Services(db).create(projects.environments(project.id).single().id, ServiceSpec("web", "Web", ServiceKind.WEB)).id
    }

    private fun admission(plan: Plan) = BuildAdmission(db, Plans(mapOf("free" to plan), "free"))

    private suspend fun admit(build: Build, orgId: UUID) = admission.admit(requireNotNull(builds.byId(build.id)), orgId)

    private suspend fun status(build: Build) = builds.byId(build.id)?.status

    @Test
    fun `with one concurrent build a second stays queued until the first finishes`() = runBlocking {
        val (orgId, serviceId) = service("acme")
        val first = builds.request(serviceId, "aaa", null, "main")
        val second = builds.request(serviceId, "bbb", null, "preview")
        assertNull(admit(first, orgId))
        assertEquals(Duration.ofSeconds(15), assertFailsWith<Redeliver> { admit(second, orgId) }.delay)
        assertEquals(BuildStatus.QUEUED, status(second))
        assertNull(admit(first, orgId))

        builds.markFailed(first.id, "the build job failed")
        assertNull(admit(second, orgId))
        assertEquals(BuildStatus.RUNNING, status(second))
    }

    @Test
    fun `a stale read neither restarts a running build nor starts a cancelled one`() = runBlocking {
        val (orgId, serviceId) = service("acme")
        val running = builds.request(serviceId, "aaa", null, "main")
        assertNull(admit(running, orgId))
        val startedAt = builds.byId(running.id)?.startedAt
        assertNull(admission.admit(running, orgId))
        assertEquals(startedAt, builds.byId(running.id)?.startedAt)

        val cancelled = builds.request(serviceId, "bbb", null, "main")
        db.tx { BuildsTable.update({ BuildsTable.id eq cancelled.id }) { it[status] = BuildStatus.CANCELLED.sql } }
        assertEquals(Duration.ZERO, assertFailsWith<Redeliver> { admission.admit(cancelled, orgId) }.delay)
        assertEquals(BuildStatus.CANCELLED, status(cancelled))
    }

    @Test
    fun `a suspended org's build is refused`() = runBlocking {
        val (orgId, serviceId) = service("acme")
        db.tx { Organizations.update({ Organizations.id eq orgId }) { it[suspendedAt] = now() } }
        val build = builds.request(serviceId, "aaa", null, "main")
        assertEquals("the organization is suspended", admit(build, orgId))
        assertEquals(BuildStatus.QUEUED, status(build))
    }

    @Test
    fun `the eleventh build in an hour is refused`() = runBlocking {
        val (orgId, serviceId) = service("acme")
        repeat(10) {
            val build = builds.request(serviceId, "sha$it", null, "main")
            assertNull(admit(build, orgId))
            builds.markFailed(build.id, "the build job failed")
        }
        val eleventh = builds.request(serviceId, "sha10", null, "main")
        assertEquals("the free plan's builds per hour limit is 10", admit(eleventh, orgId))

        db.tx { BuildsTable.update { it[startedAt] = now().minusHours(2) } }
        assertNull(admit(eleventh, orgId))
    }

    @Test
    fun `builds waiting behind a running one count against the hourly limit`() = runBlocking {
        val (orgId, serviceId) = service("acme")
        val requested = (0..10).map { builds.request(serviceId, "sha$it", null, "b$it") }
        assertNull(admit(requested.first(), orgId))
        requested.subList(1, 10).forEach { assertFailsWith<Redeliver> { admit(it, orgId) } }
        assertEquals("the free plan's builds per hour limit is 10", admit(requested.last(), orgId))
    }

    @Test
    fun `org B's build starts while org A holds twenty queued builds`() = runBlocking {
        val nats = TestNats.clean()
        val (acme, acmeService) = service("acme")
        val (rival, rivalService) = service("rival")
        val queued = (0..20).map { builds.request(acmeService, "sha$it", null, "b$it") }
        val theirs = builds.request(rivalService, "sha", null, "main")
        val roomy = admission(Plan(concurrentBuilds = 1, buildsPerHour = 25))
        val started = CompletableDeferred<Unit>()
        val consumer = nats.consume(Subject.BUILD_REQUESTED, "fairness-test", this, concurrency = 4) {
            val build = requireNotNull(builds.byId(it.uuid("buildId")))
            roomy.admit(build, if (build.serviceId == acmeService) acme else rival)?.let(::error)
            if (build.id == theirs.id) started.complete(Unit) else awaitCancellation()
        }
        (queued + theirs).forEachIndexed { i, build -> nats.publish(Subject.BUILD_REQUESTED.value, i + 1L, buildJsonObject { put("buildId", build.id.toString()) }) }
        withTimeout(10.seconds) { started.await() }
        assertEquals(1, queued.count { status(it) == BuildStatus.RUNNING })
        consumer.cancel()
    }
}
