package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.admin.Admin
import dev.liftgate.admin.OperatorOrg
import dev.liftgate.admin.OperatorSummary
import dev.liftgate.admin.OperatorUser
import dev.liftgate.auth.Access
import dev.liftgate.auth.ApiTokens
import dev.liftgate.auth.GITHUB
import dev.liftgate.auth.Mailer
import dev.liftgate.auth.Sessions
import dev.liftgate.config.EmailConfig
import dev.liftgate.db.AuditLog
import dev.liftgate.db.GitConnections
import dev.liftgate.db.Identities
import dev.liftgate.db.Users
import dev.liftgate.db.now
import dev.liftgate.org.Orgs
import dev.liftgate.org.Plan
import dev.liftgate.org.Plans
import dev.liftgate.org.UserStatus
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/30/2026
 */
class OperatorRoutesTest {
    private val db = TestDatabase.clean()
    private val orgs = Orgs(db)
    private val sessions = mockk<Sessions>()
    private val apiTokens = ApiTokens(db, mockk(relaxed = true))
    private val sent = mutableListOf<MimeMessage>()
    private val config = testConfig(mapOf("LIFTGATE_OPERATORS" to "github:Octo-Ops,ops@example.com"))
    private val mailer = Mailer(EmailConfig("mail.example.com", 587, false, null, null, "login@liftgate.dev")) { sent += it }
    private val app = mockk<App>().also {
        every { it.config } returns config
        every { it.cache } returns unlimitedCache
        every { it.db } returns db
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.orgs } returns orgs
        every { it.access } returns Access(orgs)
        every { it.sessions } returns sessions
        every { it.apiTokens } returns apiTokens
        every { it.admin } returns Admin(db, Plans(mapOf("free" to Plan(projects = 1), "unlimited" to Plan()), "free"), mailer, config.operators)
    }

    private suspend fun user(login: String, email: String? = null, status: UserStatus = UserStatus.ACTIVE, session: String = login): UUID {
        val id = db.tx { insertUser(login, null, email, null, status) }.id
        coEvery { sessions.resolve(session) } coAnswers { orgs.user(id) }
        return id
    }

    private suspend fun operator(login: String = "dean", email: String? = null) = user(login, email).also { id ->
        db.tx {
            GitConnections.insert {
                it[userId] = id
                it[provider] = GITHUB
                it[accountLogin] = "octo-ops"
                it[accessToken] = byteArrayOf(1)
            }
        }
    }

    private suspend fun identity(userId: UUID, provider: String, email: String?, verified: Boolean) = db.tx {
        Identities.insert {
            it[id] = UUID.randomUUID()
            it[Identities.userId] = userId
            it[Identities.provider] = provider
            it[subject] = UUID.randomUUID().toString()
            it[Identities.email] = email
            it[emailVerified] = verified
        }
    }

    private suspend fun HttpClient.send(method: HttpMethod, path: String, body: String? = null, credential: HttpRequestBuilder.() -> Unit = { session("dean") }) =
        request("/api/v1$path") {
            this.method = method
            credential()
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }

    private suspend fun HttpResponse.message() = json.decodeFromString(JsonObject.serializer(), bodyAsText())["message"]

    private suspend fun audit(action: String) = db.tx { AuditLog.selectAll().where { AuditLog.action eq action }.map { it[AuditLog.actorUserId] to it[AuditLog.targetId] } }

    private suspend fun status(id: UUID) = orgs.user(id)?.status

    @Test
    fun `me says whether the caller is an operator, decided from active accounts with a matching github connection or verified email`() = testApplication {
        val dean = operator()
        val ops = user("ops").also { identity(it, "email", "Ops@Example.com", true) }
        user("mallory").also { identity(it, "google", "ops@example.com", false) }
        user("octo-ops")
        user("waiting", status = UserStatus.PENDING).also { identity(it, "email", "ops@example.com", true) }
        val token = apiTokens.create(orgs.create("acme", "Acme", dean).id, "ci", dean, null)
        application { liftgate(app) }
        suspend fun flag(credential: HttpRequestBuilder.() -> Unit) = json.decodeFromString(JsonObject.serializer(), client.get("/api/v1/me", credential).bodyAsText())["operator"]
        assertEquals(
            listOf(true, true, false, false, false, false).map { JsonPrimitive(it) },
            listOf<HttpRequestBuilder.() -> Unit>({ session("dean") }, { session("ops") }, { session("mallory") }, { session("octo-ops") }, { session("waiting") }, { bearerAuth(token) })
                .map { flag(it) },
        )
        app.admin.suspendUser(ops.toString(), "compromised")
        assertEquals(false, app.admin.isOperator(ops))
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "/operator/summary") { session("ops") }.status)
    }

    @Test
    fun `every operator route answers 404 to anyone else, exactly like a route that does not exist`() = testApplication {
        val dean = operator()
        val waiting = user("waiting", "waiting@example.com", UserStatus.PENDING)
        user("member")
        orgs.create("acme", "Acme", dean)
        user("pending-ops", status = UserStatus.PENDING).also { identity(it, "email", "ops@example.com", true) }
        val token = apiTokens.create(orgs.bySlug("acme")!!.id, "ci", dean, null)
        application { liftgate(app) }
        val routes = listOf(
            HttpMethod.Get to "/operator/summary",
            HttpMethod.Get to "/operator/users",
            HttpMethod.Post to "/operator/users/$waiting/approve",
            HttpMethod.Post to "/operator/users/$waiting/suspend",
            HttpMethod.Post to "/operator/users/$waiting/unsuspend",
            HttpMethod.Get to "/operator/orgs",
            HttpMethod.Put to "/operator/orgs/acme/plan",
            HttpMethod.Post to "/operator/orgs/acme/suspend",
            HttpMethod.Post to "/operator/orgs/acme/unsuspend",
            HttpMethod.Delete to "/operator/users",
            HttpMethod.Get to "/operator/orgs/acme/suspend",
        )
        val callers = listOf<HttpRequestBuilder.() -> Unit>({}, { session("member") }, { session("pending-ops") }, { bearerAuth(token) })
        routes.forEach { (method, path) ->
            val missing = client.send(method, "/nothing${path.removePrefix("/operator")}", """{"reason":"spam","plan":"free"}""") { session("member") }
            callers.forEach { caller ->
                val response = client.send(method, path, """{"reason":"spam","plan":"free"}""", caller)
                assertEquals(missing.status to missing.bodyAsText(), response.status to response.bodyAsText(), "$method $path")
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
        }
        assertEquals(UserStatus.PENDING, status(waiting))
        assertEquals("default" to null, orgs.bySlug("acme")?.let { it.plan to it.suspendedAt })
        assertTrue(db.tx { AuditLog.selectAll().empty() })
    }

    @Test
    fun `an operator approves, suspends and unsuspends accounts, recorded as the actor and emailed to the account`() = testApplication {
        val dean = operator()
        val newbie = user("newbie", "newbie@example.com", UserStatus.PENDING)
        application { liftgate(app) }
        val path = "/operator/users/$newbie"

        assertEquals(JsonPrimitive("newbie is active"), client.send(HttpMethod.Post, "$path/approve").message())
        val again = client.send(HttpMethod.Post, "$path/approve")
        assertEquals(HttpStatusCode.Conflict to "newbie is active", again.status to json.decodeFromString(ErrorBody.serializer(), again.bodyAsText()).message)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.send(HttpMethod.Post, "$path/suspend", """{"reason":" "}""").status)
        assertEquals(JsonPrimitive("newbie is suspended"), client.send(HttpMethod.Post, "$path/suspend", """{"reason":"spam"}""").message())
        assertEquals(UserStatus.SUSPENDED, status(newbie))
        assertEquals(JsonPrimitive("newbie is active"), client.send(HttpMethod.Post, "$path/unsuspend").message())
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Post, "/operator/users/${UUID.randomUUID()}/approve").status)

        assertEquals(
            listOf("user.approve", "user.suspend", "user.unsuspend").map { listOf(dean to newbie.toString()) },
            listOf("user.approve", "user.suspend", "user.unsuspend").map { audit(it) },
        )
        assertEquals(listOf("Your Liftgate account is approved", "Your Liftgate account is suspended"), sent.map { it.subject })
        assertTrue(sent.all { it.getRecipients(Message.RecipientType.TO).single().toString() == "newbie@example.com" })
        assertTrue("spam" in sent.last().content as String)
    }

    @Test
    fun `an operator lists organizations with their counts, moves them between plans and suspends them`() = testApplication {
        val dean = operator()
        val owner = user("owner", "owner@example.com")
        val acme = orgs.create("acme", "Acme", owner)
        val projects = Projects(db)
        Services(db).create(projects.environments(projects.create(acme.id, "shop", "Shop", "acme/shop", 1).id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
        orgs.create("empty", "Empty", dean)
        application { liftgate(app) }
        suspend fun list() = json.decodeFromString<List<OperatorOrg>>(client.send(HttpMethod.Get, "/operator/orgs").bodyAsText())

        assertEquals(listOf("empty" to listOf(1L, 0L, 0L), "acme" to listOf(1L, 1L, 1L)), list().map { it.org.slug to listOf(it.members, it.projects, it.services) })
        assertEquals(JsonPrimitive("acme is on the unlimited plan"), client.send(HttpMethod.Put, "/operator/orgs/acme/plan", """{"plan":"unlimited"}""").message())
        assertEquals(HttpStatusCode.UnprocessableEntity, client.send(HttpMethod.Put, "/operator/orgs/acme/plan", """{"plan":"pro"}""").status)
        assertEquals(JsonPrimitive("acme is suspended"), client.send(HttpMethod.Post, "/operator/orgs/acme/suspend", """{"reason":"phishing"}""").message())
        assertEquals("unlimited" to "phishing", list().last().org.let { it.plan to it.suspendedReason })
        assertEquals(HttpStatusCode.Conflict, client.send(HttpMethod.Post, "/operator/orgs/acme/suspend", """{"reason":"again"}""").status)
        assertEquals(JsonPrimitive("acme is active"), client.send(HttpMethod.Post, "/operator/orgs/acme/unsuspend").message())
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Post, "/operator/orgs/ghost/unsuspend").status)

        assertEquals(listOf("org.plan", "org.suspend", "org.unsuspend").map { listOf(dean to acme.id.toString()) }, listOf("org.plan", "org.suspend", "org.unsuspend").map { audit(it) })
        assertEquals(listOf("acme is suspended on Liftgate"), sent.map { it.subject })
        assertEquals("owner@example.com", sent.single().getRecipients(Message.RecipientType.TO).single().toString())
    }

    @Test
    fun `users list pending accounts first and then the newest, filter by status and page with before`() = testApplication {
        operator(email = "dean@example.com")
        val oldest = user("oldest")
        val pending = user("pending", status = UserStatus.PENDING)
        val suspended = user("suspended", status = UserStatus.SUSPENDED)
        val newest = user("newest")
        listOf(oldest, pending, suspended, newest).forEachIndexed { minutes, id -> db.tx { Users.update({ Users.id eq id }) { it[createdAt] = now().plusMinutes(minutes.toLong()) } } }
        identity(pending, GITHUB, null, false)
        identity(pending, "email", "pending@example.com", true)
        orgs.create("oldest", "Oldest", oldest)
        application { liftgate(app) }
        suspend fun page(query: String) = json.decodeFromString<List<OperatorUser>>(client.send(HttpMethod.Get, "/operator/users?$query").bodyAsText()).map { it.user.login }

        val all = json.decodeFromString<List<OperatorUser>>(client.send(HttpMethod.Get, "/operator/users").bodyAsText())
        assertEquals(listOf("pending", "newest", "suspended", "oldest", "dean"), all.map { it.user.login })
        assertEquals(listOf("email", "github") to 1L, all.first().providers to all.single { it.user.login == "oldest" }.orgs)
        assertEquals(listOf("pending", "newest"), page("limit=2"))
        assertEquals(listOf("suspended", "oldest"), page("limit=2&before=$newest"))
        assertEquals(listOf("newest", "oldest", "dean"), page("status=active"))
        assertEquals(listOf("oldest", "dean"), page("status=active&before=$newest"))
        assertEquals(HttpStatusCode.UnprocessableEntity, client.send(HttpMethod.Get, "/operator/users?status=banned").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.send(HttpMethod.Get, "/operator/users?limit=500").status)
        assertEquals(OperatorSummary(1, listOf("free", "unlimited", "default")), json.decodeFromString<OperatorSummary>(client.send(HttpMethod.Get, "/operator/summary").bodyAsText()))
    }
}
