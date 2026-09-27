package dev.liftgate.auth

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.cache.Cache
import dev.liftgate.db.ApiTokens as ApiTokensTable
import dev.liftgate.db.AuditLog
import dev.liftgate.db.Memberships
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.http.SESSION_COOKIE
import dev.liftgate.http.json
import dev.liftgate.http.liftgate
import dev.liftgate.http.session
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.HttpClient
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.TestApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class ApiTokensTest {
    companion object {
        private val cache by lazy { Cache(testConfig()) }

        @AfterAll
        @JvmStatic
        fun close() = cache.close()
    }

    private val db = TestDatabase.clean()
    private val orgs = Orgs(db)
    private val sessions by lazy { Sessions(db, cache, orgs) }

    private val acme = runBlocking { orgs.create("acme", "Acme", db.tx { insertUser("founder", null, null, null) }.id) }

    private fun replica() = TestApplication {
        val app = mockk<App>().also {
            every { it.config } returns testConfig()
            every { it.cache } returns unlimitedCache
            every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            every { it.orgs } returns orgs
            every { it.access } returns Access(orgs)
            every { it.sessions } returns sessions
            every { it.apiTokens } returns ApiTokens(db, cache)
            every { it.sso } returns mockk<Sso>()
        }
        application { liftgate(app) }
    }

    private fun replicas(block: suspend (HttpClient, HttpClient) -> Unit) = runBlocking {
        val (a, b) = replica() to replica()
        try {
            a.start()
            b.start()
            block(a.client, b.client)
        } finally {
            a.stop()
            b.stop()
        }
    }

    private suspend fun signedIn(role: OrgRole): String {
        val user = db.tx {
            insertUser(role.sql, null, null, null).also { user ->
                Memberships.insert {
                    it[orgId] = acme.id
                    it[userId] = user.id
                    it[Memberships.role] = role.sql
                }
            }
        }
        return sessions.create(user.id)
    }

    private suspend fun HttpClient.create(session: String, body: String) = post("/api/v1/orgs/acme/tokens") {
        session(session)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.token(session: String, body: String = """{"name":"ci"}""") =
        json.parseToJsonElement(create(session, body).bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content

    private suspend fun HttpClient.list(session: String) =
        json.parseToJsonElement(get("/api/v1/orgs/acme/tokens") { cookie(SESSION_COOKIE, session) }.bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun HttpClient.me(token: String) = get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $token") }.status

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    @Test
    fun `hash is deterministic and differs per token`() {
        val hash = ApiTokens.hash("lg_abc")
        assertEquals(hash, ApiTokens.hash("lg_abc"))
        assertNotEquals(hash, ApiTokens.hash("lg_abd"))
        assertEquals(43, hash.length)
    }

    @Test
    fun `a listed token shows its metadata and last use but never the secret`() = replicas { api, _ ->
        val owner = signedIn(OrgRole.OWNER)
        val token = api.token(owner)
        assertTrue(token.startsWith("lg_") && token.length == 46)
        assertEquals(JsonNull, api.list(owner).single()["lastUsedAt"])
        assertEquals(HttpStatusCode.OK, api.me(token))
        val listed = api.list(owner).single()
        assertEquals(setOf("id", "name", "createdBy", "createdAt", "lastUsedAt", "expiresAt"), listed.keys)
        assertEquals("ci", listed.text("name"))
        assertEquals("owner", listed.getValue("createdBy").jsonObject.text("login"))
        assertTrue(Instant.parse(listed.text("lastUsedAt")) > Instant.now().minusSeconds(60))
        val expires = Instant.parse(listed.text("expiresAt"))
        assertTrue(expires > Instant.now().plus(Duration.ofDays(89)) && expires <= Instant.now().plus(Duration.ofDays(90)))
        assertTrue(token !in listed.toString() && ApiTokens.hash(token) !in listed.toString())
    }

    @Test
    fun `a revoked token is rejected on the next request by every replica`() = replicas { a, b ->
        val admin = signedIn(OrgRole.ADMIN)
        val token = a.token(admin)
        assertEquals(HttpStatusCode.OK to HttpStatusCode.OK, a.me(token) to b.me(token))
        val id = b.list(admin).single().text("id")
        assertEquals(HttpStatusCode.NoContent, b.delete("/api/v1/orgs/acme/tokens/$id") { session(admin) }.status)
        assertEquals(HttpStatusCode.Unauthorized to HttpStatusCode.Unauthorized, a.me(token) to b.me(token))
        assertEquals(HttpStatusCode.NotFound, a.delete("/api/v1/orgs/acme/tokens/$id") { session(admin) }.status)
        val audit = db.tx { AuditLog.selectAll().where { AuditLog.targetId eq id }.orderBy(AuditLog.id).map { it[AuditLog.action] to it[AuditLog.orgId] } }
        assertEquals(listOf("token.create" to acme.id, "token.revoke" to acme.id), audit)
    }

    @Test
    fun `an expired token is rejected by every replica and create input is bounded`() = replicas { a, b ->
        val owner = signedIn(OrgRole.OWNER)
        val token = a.token(owner, """{"name":"deploy","expiresInDays":1}""")
        assertEquals(HttpStatusCode.OK, b.me(token))
        db.tx { ApiTokensTable.update({ ApiTokensTable.tokenHash eq ApiTokens.hash(token) }) { it[expiresAt] = now().minusSeconds(1) } }
        assertEquals(HttpStatusCode.Unauthorized to HttpStatusCode.Unauthorized, a.me(token) to b.me(token))
        a.token(owner, """{"name":"forever","expiresInDays":null}""")
        assertEquals(JsonNull, a.list(owner).single { it.text("name") == "forever" }["expiresAt"])
        listOf("""{"name":"x","expiresInDays":0}""", """{"name":"x","expiresInDays":366}""", """{"name":" "}""", """{"name":"${"x".repeat(101)}"}""").forEach {
            assertEquals(HttpStatusCode.UnprocessableEntity, a.create(owner, it).status, it)
        }
    }

    @Test
    fun `members and token principals cannot manage tokens or sso`() = replicas { api, _ ->
        val owner = signedIn(OrgRole.OWNER)
        val member = signedIn(OrgRole.MEMBER)
        val token = api.token(owner)
        val id = api.list(owner).single().text("id")
        assertEquals(HttpStatusCode.Forbidden, api.get("/api/v1/orgs/acme/tokens") { cookie(SESSION_COOKIE, member) }.status)
        assertEquals(HttpStatusCode.Forbidden, api.delete("/api/v1/orgs/acme/tokens/$id") { session(member) }.status)
        assertEquals(HttpStatusCode.Forbidden, api.create(member, """{"name":"ci"}""").status)
        val bearer = listOf(
            api.post("/api/v1/orgs/acme/tokens") { header(HttpHeaders.Authorization, "Bearer $token") },
            api.get("/api/v1/orgs/acme/tokens") { header(HttpHeaders.Authorization, "Bearer $token") },
            api.delete("/api/v1/orgs/acme/tokens/$id") { header(HttpHeaders.Authorization, "Bearer $token") },
            api.put("/api/v1/orgs/acme/sso") { header(HttpHeaders.Authorization, "Bearer $token") },
        )
        assertEquals(List(4) { HttpStatusCode.Forbidden }, bearer.map { it.status })
        assertEquals(1, api.list(owner).size)
    }
}
