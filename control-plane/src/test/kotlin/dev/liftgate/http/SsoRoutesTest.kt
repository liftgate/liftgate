package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.SignedIn
import dev.liftgate.auth.Sso
import dev.liftgate.testConfig
import io.ktor.client.request.cookie
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/18/2026
 */
class SsoRoutesTest {
    private val sso = mockk<Sso> {
        coEvery { acs("acme", "PHNhbWxwOlJlc3BvbnNlLz4=", "nonce") } returns (SignedIn(UUID.randomUUID(), "session-9") to "/acme/web")
        coEvery { acs("acme", "PHNhbWxwOlJlc3BvbnNlLz4=", "") } throws LiftgateException(HttpStatusCode.BadRequest, "invalid_saml", "another browser")
        coEvery { lookup("dean@acme.com") } returns "acme"
        coEvery { lookup("dean@other.com") } returns null
    }
    private val app = mockk<App>().also {
        every { it.config } returns testConfig()
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.sso } returns sso
    }

    @Test
    fun `acs starts a session and returns to next on the dashboard`() = testApplication {
        application { liftgate(app) }
        val response = createClient { followRedirects = false }
            .submitForm("/api/v1/auth/sso/acme/acs", parameters { append("SAMLResponse", "PHNhbWxwOlJlc3BvbnNlLz4=") }) { cookie("liftgate_saml", "nonce") }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("${testConfig().dashboardUrl}/acme/web", response.headers[HttpHeaders.Location])
        assertTrue(response.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$SESSION_COOKIE=session-9") })
    }

    @Test
    fun `a rejected response returns to login with the error`() = testApplication {
        application { liftgate(app) }
        val response = createClient { followRedirects = false }
            .submitForm("/api/v1/auth/sso/acme/acs", parameters { append("SAMLResponse", "PHNhbWxwOlJlc3BvbnNlLz4=") })
        assertEquals("${testConfig().dashboardUrl}/login?error=invalid_saml", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `lookup finds the organization for an email domain`() = testApplication {
        application { liftgate(app) }
        assertEquals("""{"org":"acme"}""", client.get("/api/v1/auth/sso/lookup?email=dean@acme.com").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/auth/sso/lookup?email=dean@other.com").status)
    }
}
