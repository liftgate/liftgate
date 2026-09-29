package dev.liftgate.org

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.auth.Access
import dev.liftgate.auth.ApiTokens
import dev.liftgate.auth.Mailer
import dev.liftgate.auth.OrgRole
import dev.liftgate.auth.Sessions
import dev.liftgate.config.EmailConfig
import dev.liftgate.db.Invitations as InvitationsTable
import dev.liftgate.db.Memberships
import dev.liftgate.db.Organizations
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.http.ErrorBody
import dev.liftgate.http.json
import dev.liftgate.http.liftgate
import dev.liftgate.http.session
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.mail.Message
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DASHBOARD = "https://dashboard.liftgate.test"

/**
 * @author Dean
 * @date 9/27/2026
 */
class InvitationsTest {
    private val db = TestDatabase.clean()
    private val orgs = Orgs(db)
    private val sessions = mockk<Sessions>()
    private val apiTokens = ApiTokens(db, mockk(relaxed = true))
    private val sent = mutableListOf<MimeMessage>()
    private val app = mockk<App>().also {
        every { it.config } returns testConfig()
        every { it.cache } returns unlimitedCache
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.db } returns db
        every { it.orgs } returns orgs
        every { it.access } returns Access(orgs)
        every { it.sessions } returns sessions
        every { it.apiTokens } returns apiTokens
        every { it.invitations } returns Invitations(db, Mailer(EmailConfig("mail.example.com", 587, false, null, null, "login@liftgate.dev")) { sent += it }, DASHBOARD)
    }
    private val owner = user("owner")
    private val acme = runBlocking { orgs.create("acme", "Acme", owner) }

    private fun user(login: String, role: OrgRole? = null): UUID = runBlocking {
        db.tx {
            insertUser(login, null, null, null).id.also { id ->
                role?.let {
                    Memberships.insert {
                        it[orgId] = acme.id
                        it[userId] = id
                        it[Memberships.role] = role.sql
                    }
                }
            }
        }
    }.also { id -> coEvery { sessions.resolve(login) } coAnswers { orgs.user(id) } }

    private suspend fun HttpClient.invite(session: String, role: String, email: String? = null) = post("/api/v1/orgs/acme/invitations") {
        session(session)
        contentType(ContentType.Application.Json)
        setBody(if (email == null) """{"role":"$role"}""" else """{"role":"$role","email":"$email"}""")
    }

    private suspend fun HttpClient.setRole(session: String, userId: UUID, role: String) = patch("/api/v1/orgs/acme/members/$userId") {
        session(session)
        contentType(ContentType.Application.Json)
        setBody("""{"role":"$role"}""")
    }

    private suspend fun HttpClient.accept(session: String, invitation: CreatedInvitation) =
        post("/api/v1/invitations/${invitation.url.substringAfterLast('/')}/accept") { session(session) }

    private suspend fun HttpResponse.invitation() = json.decodeFromString(CreatedInvitation.serializer(), bodyAsText())

    private suspend fun HttpResponse.error() = status to json.decodeFromString(ErrorBody.serializer(), bodyAsText()).error

    @Test
    fun `an accepted invitation joins with the invited role and the link works once`() = testApplication {
        application { liftgate(app) }
        val created = client.invite("owner", "admin", "New@Liftgate.test")
        assertEquals(HttpStatusCode.Created, created.status)
        val invitation = created.invitation()
        assertTrue(invitation.url.startsWith("$DASHBOARD/account/invitations/") && invitation.emailed)
        val mail = sent.single()
        assertEquals("new@liftgate.test", mail.getRecipients(Message.RecipientType.TO).single().toString())
        assertTrue(invitation.url in mail.content as String)

        val newbie = user("newbie")
        val preview = client.get("/api/v1/invitations/${invitation.url.substringAfterLast('/')}")
        assertEquals(InvitationPreview("acme", "Acme", OrgRole.ADMIN, "owner"), json.decodeFromString(InvitationPreview.serializer(), preview.bodyAsText()))
        val joined = client.accept("newbie", invitation)
        assertEquals(HttpStatusCode.OK, joined.status)
        assertEquals(OrgRole.ADMIN, json.decodeFromString(Organization.serializer(), joined.bodyAsText()).role)
        assertEquals(OrgRole.ADMIN, orgs.role(acme.id, newbie))

        val late = user("late")
        assertEquals(HttpStatusCode.Gone to "invitation_gone", client.accept("late", invitation).error())
        assertEquals(HttpStatusCode.Gone, client.get("/api/v1/invitations/${invitation.url.substringAfterLast('/')}").status)
        assertNull(orgs.role(acme.id, late))
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/invitations/nope/accept") { session("late") }.status)
    }

    @Test
    fun `an expired link returns 410`() = testApplication {
        application { liftgate(app) }
        val invitation = client.invite("owner", "member").invitation()
        db.tx { InvitationsTable.update { it[expiresAt] = now().minusSeconds(1) } }
        val newbie = user("newbie")
        assertEquals(HttpStatusCode.Gone to "invitation_gone", client.accept("newbie", invitation).error())
        assertNull(orgs.role(acme.id, newbie))
    }

    @Test
    fun `nobody invites above their own role and members and tokens cannot invite`() = testApplication {
        application { liftgate(app) }
        user("admin", OrgRole.ADMIN)
        user("member", OrgRole.MEMBER)
        assertEquals(HttpStatusCode.Forbidden, client.invite("admin", "owner").status)
        assertEquals(HttpStatusCode.Created, client.invite("admin", "admin").status)
        assertEquals(HttpStatusCode.Forbidden, client.invite("member", "member").status)
        val token = apiTokens.create(acme.id, "ci", owner, null)
        val viaToken = client.post("/api/v1/orgs/acme/invitations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"role":"member"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, viaToken.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.invite("owner", "member", "not an email").status)
    }

    @Test
    fun `a link dies with its sender's right to grant the role`() = testApplication {
        application { liftgate(app) }
        val admin = user("admin", OrgRole.ADMIN)
        val invitation = client.invite("admin", "admin").invitation()
        assertEquals(HttpStatusCode.NoContent, client.setRole("owner", admin, "member").status)
        user("newbie")
        assertEquals(HttpStatusCode.Gone, client.accept("newbie", invitation).status)
    }

    @Test
    fun `the last owner can be neither removed nor demoted`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.Conflict to "last_owner", client.setRole("owner", owner, "admin").error())
        assertEquals(HttpStatusCode.Conflict to "last_owner", client.delete("/api/v1/orgs/acme/members/$owner") { session("owner") }.error())
        assertEquals(HttpStatusCode.Conflict to "last_owner", client.delete("/api/v1/orgs/acme/members/me") { session("owner") }.error())
        val second = user("second", OrgRole.OWNER)
        assertEquals(HttpStatusCode.NoContent, client.setRole("owner", owner, "member").status)
        assertEquals(OrgRole.MEMBER, orgs.role(acme.id, owner))
        assertEquals(HttpStatusCode.Forbidden, client.setRole("owner", second, "member").status)
        assertEquals(HttpStatusCode.Conflict to "last_owner", client.delete("/api/v1/orgs/acme/members/me") { session("second") }.error())
        assertEquals(HttpStatusCode.NotFound, client.setRole("second", UUID.randomUUID(), "admin").status)
    }

    @Test
    fun `nobody becomes an owner past the plan's owned organizations limit`() = testApplication {
        val limits = Limits(Plans(mapOf("free" to Plan(ownedOrgs = 1)), "free"))
        val limited = Orgs(db, limits)
        every { app.orgs } returns limited
        every { app.invitations } returns Invitations(db, null, DASHBOARD, limits)
        application { liftgate(app) }
        val member = user("member", OrgRole.MEMBER)
        val outsider = user("outsider")
        limited.create("member-co", "Member Co", member)
        limited.create("outsider-co", "Outsider Co", outsider)
        assertEquals(HttpStatusCode.NoContent, client.setRole("owner", owner, "owner").status)
        assertEquals(HttpStatusCode.Conflict to "plan_limit", client.setRole("owner", member, "owner").error())
        assertEquals(OrgRole.MEMBER, orgs.role(acme.id, member))
        val invitation = client.invite("owner", "owner").invitation()
        assertEquals(HttpStatusCode.Conflict to "plan_limit", client.accept("outsider", invitation).error())
        assertNull(orgs.role(acme.id, outsider))
        val newbie = user("newbie")
        assertEquals(HttpStatusCode.OK, client.accept("newbie", invitation).status)
        assertEquals(OrgRole.OWNER, orgs.role(acme.id, newbie))
    }

    @Test
    fun `nobody leaves or joins a suspended organization`() = testApplication {
        application { liftgate(app) }
        val second = user("second", OrgRole.OWNER)
        val invitation = client.invite("owner", "member").invitation()
        db.tx { Organizations.update({ Organizations.id eq acme.id }) { it[suspendedAt] = now() } }
        assertEquals(HttpStatusCode.Forbidden to "org_suspended", client.delete("/api/v1/orgs/acme/members/me") { session("second") }.error())
        assertEquals(listOf(OrgRole.OWNER), orgs.forUser(second).map { it.role })
        user("newbie")
        assertEquals(HttpStatusCode.Forbidden to "org_suspended", client.accept("newbie", invitation).error())
    }

    @Test
    fun `a removed member's tokens stop authenticating and members can leave`() = testApplication {
        application { liftgate(app) }
        val admin = user("admin", OrgRole.ADMIN)
        val member = user("member", OrgRole.MEMBER)
        val token = apiTokens.create(acme.id, "ci", admin, null)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/orgs/acme/members/$member") { session("admin") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/orgs/acme/members/me") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/orgs/acme/members/$admin") { session("owner") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/orgs/acme/members/me") { session("member") }.status)
        assertEquals(listOf("owner"), orgs.members(acme.id).map { it.first.login })
    }

    @Test
    fun `without mail the link is still returned and organizations carry the caller's role`() = testApplication {
        every { app.invitations } returns Invitations(db, null, DASHBOARD)
        application { liftgate(app) }
        val invitation = client.invite("owner", "member", "new@liftgate.test").invitation()
        assertFalse(invitation.emailed)
        user("newbie")
        assertEquals(HttpStatusCode.OK, client.accept("newbie", invitation).status)
        assertEquals(HttpStatusCode.Conflict, client.accept("newbie", client.invite("owner", "member").invitation()).status)
        val listed = json.decodeFromString(ListSerializer(Organization.serializer()), client.get("/api/v1/orgs") { session("newbie") }.bodyAsText())
        assertEquals(listOf(OrgRole.MEMBER), listed.map { it.role })
        assertEquals(OrgRole.OWNER, json.decodeFromString(Organization.serializer(), client.get("/api/v1/orgs/acme") { session("owner") }.bodyAsText()).role)
    }
}
