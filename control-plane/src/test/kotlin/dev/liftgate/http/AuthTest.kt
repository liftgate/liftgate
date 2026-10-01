package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.ApiTokens
import dev.liftgate.auth.Sessions
import dev.liftgate.org.Orgs
import dev.liftgate.org.User
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class AuthTest {
    private val sessions = mockk<Sessions>()
    private val app = mockk<App>().also {
        every { it.sessions } returns sessions
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.config } returns testConfig()
        every { it.cache } returns unlimitedCache
    }

    @Test
    fun `requests without credentials are rejected`() = testApplication {
        application { liftgate(app) }
        val response = client.get("/api/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("""{"error":"unauthorized","message":"authentication required"}""", response.bodyAsText())
    }

    @Test
    fun `api errors keep their status and json body when the client asks for html`() = testApplication {
        application { liftgate(app) }
        val response = client.get("/api/v1/me") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals("""{"error":"unauthorized","message":"authentication required"}""", response.bodyAsText())
    }

    @Test
    fun `api responses forbid framing and sniffing and name no server version`() = testApplication {
        application { liftgate(app) }
        val headers = client.get("/api/v1/me").headers
        assertEquals("default-src 'none'; frame-ancestors 'none'", headers["Content-Security-Policy"])
        assertEquals("nosniff", headers["X-Content-Type-Options"])
        assertEquals("strict-origin-when-cross-origin", headers["Referrer-Policy"])
        assertEquals("max-age=63072000", headers[HttpHeaders.StrictTransportSecurity])
        assertEquals("Liftgate", headers[HttpHeaders.Server])
    }

    @Test
    fun `a session cookie resolves the user`() = testApplication {
        val user = User(UUID.randomUUID(), "dean", "Dean", null, "https://avatars.example/dean")
        coEvery { sessions.resolve("session-1") } returns user
        application { liftgate(app) }
        val response = client.get("/api/v1/me") { cookie(SESSION_COOKIE, "session-1") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(json.encodeToString(User.serializer(), user.copy(operator = false)), response.bodyAsText())
    }

    @Test
    fun `only the dashboard origin may send credentialed cross-origin requests`() = testApplication {
        application { liftgate(app) }
        suspend fun preflight(origin: String) = client.options("/api/v1/me") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.AccessControlRequestMethod, "PUT")
        }
        val allowed = preflight("http://localhost:3000")
        assertEquals("http://localhost:3000", allowed.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", allowed.headers[HttpHeaders.AccessControlAllowCredentials])
        assertNull(preflight("https://evil.example").headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `api tokens cannot create organizations or mint further tokens`() = testApplication {
        val user = User(UUID.randomUUID(), "dean", null, null, null)
        every { app.apiTokens } returns mockk<ApiTokens> { coEvery { resolve("lg_token") } returns (UUID.randomUUID() to user.id) }
        every { app.orgs } returns mockk<Orgs> { coEvery { user(user.id) } returns user }
        application { liftgate(app) }
        listOf("/api/v1/orgs", "/api/v1/orgs/acme/tokens").forEach {
            assertEquals(HttpStatusCode.Forbidden, client.post(it) { header(HttpHeaders.Authorization, "Bearer lg_token") }.status, it)
        }
    }

    @Test
    fun `an unknown session is rejected`() = testApplication {
        coEvery { sessions.resolve("stale") } returns null
        application { liftgate(app) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { cookie(SESSION_COOKIE, "stale") }.status)
    }

    @Test
    fun `the session cookie drops its host prefix and secure flag only for a plain http localhost api`() {
        coEvery { sessions.resolve("session-1") } returns User(UUID.randomUUID(), "dean", null, null, null)
        coEvery { sessions.delete("session-1") } just Runs
        val cases = mapOf(
            "https://liftgate.example.com" to "__Host-liftgate_session",
            "http://liftgate.example.com" to "__Host-liftgate_session",
            "http://localhost:8080" to "liftgate_session",
            "http://127.0.0.1:8080" to "liftgate_session",
        )
        cases.forEach { (publicUrl, name) ->
            val secure = name != SESSION_COOKIE
            every { app.config } returns testConfig(mapOf("LIFTGATE_PUBLIC_URL" to publicUrl, "LIFTGATE_DATABASE_PASSWORD" to "secret"))
            testApplication {
                application {
                    liftgate(app)
                    routing {
                        get("/start") {
                            call.startSession(app, "session-1")
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                }
                val started = client.get("/start").headers[HttpHeaders.SetCookie].orEmpty()
                assertTrue(started.startsWith("$name=session-1;") && ("Secure" in started) == secure, started)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { cookie(name, "session-1") }.status)
                if (secure) assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { cookie(SESSION_COOKIE, "session-1") }.status)
                val logout = client.post("/api/v1/auth/logout") {
                    cookie(name, "session-1")
                    header(HttpHeaders.Origin, "http://localhost:3000")
                }
                val cleared = logout.headers[HttpHeaders.SetCookie].orEmpty()
                assertTrue(cleared.startsWith("$name=;") && ("Secure" in cleared) == secure, cleared)
            }
        }
        coVerify(exactly = cases.size) { sessions.delete("session-1") }
    }
}
