package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.admin.Admin
import dev.liftgate.auth.Access
import dev.liftgate.auth.ApiTokens
import dev.liftgate.auth.GITHUB
import dev.liftgate.auth.OAuth
import dev.liftgate.auth.Sessions
import dev.liftgate.auth.SignIn
import dev.liftgate.auth.VerifiedIdentity
import dev.liftgate.config.Signup
import dev.liftgate.db.Identities
import dev.liftgate.db.Memberships
import dev.liftgate.db.Organizations
import dev.liftgate.db.Outbox
import dev.liftgate.db.Passkeys
import dev.liftgate.db.Sessions as SessionsTable
import dev.liftgate.db.Users
import dev.liftgate.db.now
import dev.liftgate.events.Subject
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.testConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/27/2026
 */
class AccountRoutesTest {
    private val db = TestDatabase.clean()
    private val orgs = Orgs(db)
    private val sessions = mockk<Sessions>()
    private val app = mockk<App>().also {
        every { it.config } returns testConfig()
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.orgs } returns orgs
        every { it.access } returns Access(orgs)
        every { it.sessions } returns sessions
        every { it.apiTokens } returns ApiTokens(db)
    }

    private fun session(id: String, userId: UUID) = coEvery { sessions.resolve(id) } coAnswers { orgs.user(userId) }

    private suspend fun user(login: String) = db.tx { insertUser(login, null, null, null) }.id

    private fun HttpRequestBuilder.session(id: String) = cookie(SESSION_COOKIE, id)

    private suspend fun HttpResponse.error() = json.decodeFromString(ErrorBody.serializer(), bodyAsText()).error

    private suspend fun teardowns() = db.tx { Outbox.selectAll().where { Outbox.subject eq Subject.TEARDOWN_REQUESTED.value }.map { it[Outbox.payload].getValue("namespace").jsonPrimitive.content } }

    @Test
    fun `a pending user gets account_pending creating an organization and 201 once approved`() = testApplication {
        val signIn = SignIn(db, mockk<Sessions> { coEvery { create(any()) } returns "s" }, Signup.APPROVAL)
        val userId = signIn.complete(VerifiedIdentity(GITHUB, "7", null, false, login = "newbie")).userId
        session("s", userId)
        application { liftgate(app) }
        suspend fun create() = client.post("/api/v1/orgs") {
            session("s")
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"newbie","name":"Newbie"}""")
        }
        val refused = create()
        assertEquals(HttpStatusCode.Forbidden to "account_pending", refused.status to refused.error())
        assertTrue(""""status":"pending"""" in client.get("/api/v1/me") { session("s") }.bodyAsText())
        Admin(db).run(listOf("approve", userId.toString()))
        assertEquals(HttpStatusCode.Created, create().status)
    }

    @Test
    fun `a suspended user's sessions and tokens return 401`() = testApplication {
        val spammer = user("spammer")
        val token = ApiTokens(db).create(orgs.create("spam", "Spam", spammer).id, "ci", spammer)
        session("s", spammer)
        application { liftgate(app) }
        suspend fun me(credential: HttpRequestBuilder.() -> Unit) = client.get("/api/v1/me", credential).status
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK), listOf(me { session("s") }, me { bearerAuth(token) }))
        Admin(db).run(listOf("suspend-user", "spammer", "spam"))
        assertEquals(listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Unauthorized), listOf(me { session("s") }, me { bearerAuth(token) }))
    }

    @Test
    fun `an account is not deleted while it co-owns an organization and otherwise leaves nothing to sign in with`() = testApplication {
        val signIn = SignIn(db, mockk<Sessions> { coEvery { create(any()) } returns "s" }, Signup.OPEN)
        val dean = signIn.complete(VerifiedIdentity(GITHUB, "1", null, false, login = "dean")).userId
        val member = user("member")
        val shared = orgs.create("shared", "Shared", dean)
        val projects = Projects(db)
        val namespace = projects.environments(projects.create(orgs.create("solo", "Solo", dean).id, "shop", "Shop", "dean/shop", 1).id).single().namespace
        db.tx {
            Memberships.insert {
                it[orgId] = shared.id
                it[userId] = member
                it[role] = "member"
            }
            Passkeys.insert {
                it[id] = UUID.randomUUID()
                it[userId] = dean
                it[credentialId] = byteArrayOf(1)
                it[publicKey] = byteArrayOf(2)
                it[signatureCount] = 0
                it[name] = "laptop"
            }
            SessionsTable.insert {
                it[id] = "s"
                it[userId] = dean
                it[expiresAt] = now().plusDays(1)
            }
        }
        val token = ApiTokens(db).create(shared.id, "ci", dean)
        session("s", dean)
        application { liftgate(app) }

        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/me") { bearerAuth(token) }.status)
        val refused = client.delete("/api/v1/me") { session("s") }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("shared" in refused.bodyAsText())

        db.tx { Memberships.deleteWhere { userId eq member } }
        val deleted = client.delete("/api/v1/me") { session("s") }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertTrue(deleted.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$SESSION_COOKIE=;") })
        db.tx {
            assertTrue(Identities.selectAll().where { Identities.userId eq dean }.empty())
            assertTrue(Passkeys.selectAll().where { Passkeys.userId eq dean }.empty())
            assertTrue(SessionsTable.selectAll().where { SessionsTable.userId eq dean }.empty())
            assertEquals(listOf("member"), Users.selectAll().map { it[Users.login] })
            assertTrue(Organizations.selectAll().empty())
        }
        assertEquals(listOf(namespace), teardowns())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { session("s") }.status)

        session("m", member)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/me") { session("m") }.status)
        assertTrue(db.tx { Users.selectAll().empty() })
    }

    @Test
    fun `only an owner's session deletes an organization, not while it is suspended, and its environments are torn down`() = testApplication {
        val owner = user("owner")
        val member = user("member")
        val org = orgs.create("acme", "Acme", owner)
        val projects = Projects(db)
        val namespace = projects.environments(projects.create(org.id, "shop", "Shop", "acme/shop", 1).id).single().namespace
        db.tx {
            Memberships.insert {
                it[orgId] = org.id
                it[userId] = member
                it[role] = "admin"
            }
        }
        val token = ApiTokens(db).create(org.id, "ci", owner)
        session("owner", owner)
        session("member", member)
        application { liftgate(app) }
        suspend fun delete(credential: HttpRequestBuilder.() -> Unit) = client.delete("/api/v1/orgs/acme", credential)

        assertEquals(HttpStatusCode.Forbidden, delete { session("member") }.status)
        assertEquals(HttpStatusCode.Forbidden, delete { bearerAuth(token) }.status)
        Admin(db).run(listOf("suspend", "acme", "phishing"))
        assertEquals("org_suspended", delete { session("owner") }.error())
        Admin(db).run(listOf("unsuspend", "acme"))
        assertEquals(HttpStatusCode.NoContent, delete { session("owner") }.status)
        assertNull(orgs.bySlug("acme"))
        assertEquals(listOf(namespace), teardowns())
    }

    @Test
    fun `providers carry the legal urls only when they are configured`() = testApplication {
        every { app.oauth } returns mockk<OAuth> { every { providers } returns emptyMap() }
        every { app.emailCodes } returns null
        application { liftgate(app) }
        assertFalse("Url" in client.get("/api/v1/auth/providers").bodyAsText())
        every { app.config } returns testConfig(mapOf("LIFTGATE_TERMS_URL" to "https://liftgate.dev/legal/terms", "LIFTGATE_AUP_URL" to "https://liftgate.dev/legal/aup"))
        val providers = json.decodeFromString(AuthProviders.serializer(), client.get("/api/v1/auth/providers").bodyAsText())
        assertEquals(listOf("https://liftgate.dev/legal/terms", null, "https://liftgate.dev/legal/aup"), listOf(providers.termsUrl, providers.privacyUrl, providers.aupUrl))
    }
}
