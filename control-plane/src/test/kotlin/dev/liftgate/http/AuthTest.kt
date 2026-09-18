package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.ApiTokens
import dev.liftgate.auth.Sessions
import dev.liftgate.org.Orgs
import dev.liftgate.org.User
import dev.liftgate.testConfig
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * @author Dean
 * @date 9/17/2026
 */
class AuthTest {
    private val sessions = mockk<Sessions>()
    private val app = mockk<App>().also {
        every { it.sessions } returns sessions
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.oauth } returns null
        every { it.config } returns testConfig()
    }

    @Test
    fun `requests without credentials are rejected`() = testApplication {
        application { liftgate(app) }
        val response = client.get("/api/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("""{"error":"unauthorized","message":"authentication required"}""", response.bodyAsText())
    }

    @Test
    fun `a session cookie resolves the user`() = testApplication {
        val user = User(UUID.randomUUID(), 42, "dean", "Dean", null, "https://avatars.example/dean")
        coEvery { sessions.resolve("session-1") } returns user
        application { liftgate(app) }
        val response = client.get("/api/v1/me") { cookie(SESSION_COOKIE, "session-1") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(json.encodeToString(User.serializer(), user), response.bodyAsText())
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
        val user = User(UUID.randomUUID(), 42, "dean", null, null, null)
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
}
