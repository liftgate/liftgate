package dev.liftgate.admin

import dev.liftgate.TestDatabase
import dev.liftgate.auth.ApiTokens
import dev.liftgate.db.AuditLog
import dev.liftgate.db.Outbox
import dev.liftgate.db.Sessions
import dev.liftgate.db.now
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Builds
import dev.liftgate.events.Subject
import dev.liftgate.org.DEFAULT_PLAN
import dev.liftgate.org.Orgs
import dev.liftgate.org.Plan
import dev.liftgate.org.Plans
import dev.liftgate.org.UserStatus
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/27/2026
 */
class AdminTest {
    private val db = TestDatabase.clean()
    private val orgs = Orgs(db)

    private suspend fun exec(vararg args: String) = Admin(db).run(args.toList())

    private suspend fun outbox(subject: Subject) = db.tx { Outbox.selectAll().where { Outbox.subject eq subject.value }.map { it[Outbox.payload] } }

    private suspend fun audit(action: String) = db.tx { AuditLog.selectAll().where { AuditLog.action eq action }.map { it[AuditLog.targetId] to it[AuditLog.details] } }

    private fun json(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (key, value) -> key to JsonPrimitive(value) })

    private suspend fun service(owner: UUID, slug: String, installationId: Long): Service {
        val org = orgs.create(slug, slug, owner)
        val projects = Projects(db)
        val project = projects.create(org.id, "shop", "Shop", "$slug/shop", installationId)
        return Services(db).create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
    }

    @Test
    fun `approve activates a pending user once and records it`() = runBlocking {
        val newbie = db.tx { insertUser("newbie", null, "newbie@example.dev", null, UserStatus.PENDING) }
        db.tx { insertUser("dean", null, null, null) }
        assertEquals(listOf(newbie.id.toString()), exec("list-pending").lines().map { it.substringBefore(' ') })
        assertEquals("newbie is active", exec("approve", "Newbie@Example.dev"))
        assertEquals("no pending users", exec("list-pending"))
        assertEquals(UserStatus.ACTIVE, orgs.user(newbie.id)?.status)
        assertEquals(listOf(json("status" to "active", "userId" to newbie.id.toString())), outbox(Subject.USER_UPDATED))
        assertEquals(listOf(newbie.id.toString() to json("status" to "active")), audit("user.approve"))
        assertEquals("newbie is active", assertFailsWith<IllegalStateException> { exec("approve", newbie.id.toString()) }.message)
    }

    @Test
    fun `suspend records the reason and cancels only the org's queued builds, unsuspend clears it`() = runBlocking {
        val owner = db.tx { insertUser("dean", null, null, null) }.id
        val acme = service(owner, "acme", 1)
        val other = service(owner, "other", 2)
        val builds = Builds(db)
        val queued = builds.request(acme.id, "aaa", null, "main")
        val running = builds.request(acme.id, "bbb", null, "main").also { builds.markRunning(it.id) }
        val elsewhere = builds.request(other.id, "aaa", null, "main")

        assertEquals("acme is suspended", exec("suspend", "acme", "crypto", "mining"))
        val org = assertNotNull(orgs.bySlug("acme"))
        assertNotNull(org.suspendedAt)
        assertEquals("crypto mining", org.suspendedReason)
        assertEquals(listOf(BuildStatus.CANCELLED, BuildStatus.RUNNING, BuildStatus.QUEUED), listOf(queued, running, elsewhere).map { builds.byId(it.id)?.status })
        assertEquals(listOf(json("reason" to "crypto mining", "orgId" to org.id.toString())), outbox(Subject.ORG_SUSPENDED))
        assertEquals(listOf(org.id.toString() to json("reason" to "crypto mining")), audit("org.suspend"))
        assertEquals("acme is already suspended", assertFailsWith<IllegalStateException> { exec("suspend", "acme", "again") }.message)

        assertEquals("acme is active", exec("unsuspend", "acme"))
        assertNull(orgs.bySlug("acme")?.suspendedAt)
        assertEquals(listOf(json("orgId" to org.id.toString())), outbox(Subject.ORG_UNSUSPENDED))
        assertEquals(1, audit("org.unsuspend").size)
        assertEquals("acme is not suspended", assertFailsWith<IllegalStateException> { exec("unsuspend", "acme") }.message)
    }

    @Test
    fun `suspending a user deletes their sessions and api tokens`() = runBlocking {
        val spammer = db.tx { insertUser("spammer", null, null, null) }
        val tokens = ApiTokens(db, mockk(relaxed = true))
        val token = tokens.create(orgs.create("spam", "Spam", spammer.id).id, "ci", spammer.id, null)
        db.tx {
            Sessions.insert {
                it[id] = "session"
                it[userId] = spammer.id
                it[expiresAt] = now().plusDays(1)
            }
        }
        assertEquals("spammer is suspended", exec("suspend-user", "spammer", "spam"))
        assertEquals(UserStatus.SUSPENDED, orgs.user(spammer.id)?.status)
        assertNull(tokens.resolve(token))
        assertEquals(0L, db.tx { Sessions.selectAll().count() })
        assertEquals(listOf(spammer.id.toString() to json("status" to "suspended", "reason" to "spam")), audit("user.suspend"))
        assertEquals("spammer is active", exec("unsuspend-user", spammer.id.toString()))
    }

    @Test
    fun `plan moves an org onto a configured plan and back to the default, and re-renders it`() = runBlocking {
        val org = orgs.create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id)
        val admin = Admin(db, Plans(mapOf("free" to Plan(projects = 1), "unlimited" to Plan()), "free"))
        assertEquals(DEFAULT_PLAN, org.plan)
        assertEquals("acme is on the unlimited plan", admin.run(listOf("plan", "acme", "unlimited")))
        assertEquals("unlimited", orgs.bySlug("acme")?.plan)
        assertEquals("acme is on the free plan", admin.run(listOf("plan", "acme", "default")))
        assertEquals(DEFAULT_PLAN, orgs.bySlug("acme")?.plan)
        val orgId = org.id.toString()
        assertEquals(setOf(json("plan" to "unlimited", "orgId" to orgId), json("plan" to DEFAULT_PLAN, "orgId" to orgId)), outbox(Subject.ORG_PLAN_CHANGED).toSet())
        assertEquals(setOf(orgId to json("plan" to "unlimited"), orgId to json("plan" to DEFAULT_PLAN)), audit("org.plan").toSet())
        assertEquals("pro is not a plan, choose one of free, unlimited, default", assertFailsWith<IllegalStateException> { admin.run(listOf("plan", "acme", "pro")) }.message)
        assertTrue("usage: admin" in assertFailsWith<IllegalStateException> { admin.run(listOf("plan", "acme")) }.message.orEmpty())
    }

    @Test
    fun `missing arguments print the usage and unknown targets are named`() = runBlocking {
        listOf(emptyList(), listOf("approve"), listOf("suspend", "acme"), listOf("promote", "dean")).forEach {
            assertTrue("usage: admin" in assertFailsWith<IllegalStateException> { Admin(db).run(it) }.message.orEmpty(), it.toString())
        }
        assertEquals("no user matches ghost", assertFailsWith<IllegalStateException> { exec("approve", "ghost") }.message)
        assertEquals("no organization ghost", assertFailsWith<IllegalStateException> { exec("unsuspend", "ghost") }.message)
    }
}
