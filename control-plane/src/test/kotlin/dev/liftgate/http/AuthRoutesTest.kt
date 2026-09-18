package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.GitConnections
import dev.liftgate.auth.OAuth
import dev.liftgate.auth.OAuthProviders
import dev.liftgate.auth.OAuthTokens
import dev.liftgate.auth.Passkeys
import dev.liftgate.auth.Sessions
import dev.liftgate.auth.SignIn
import dev.liftgate.auth.SignedIn
import dev.liftgate.auth.VerifiedIdentity
import dev.liftgate.auth.pkceChallenge
import dev.liftgate.config.GitHubConfig
import dev.liftgate.config.OAuthClient
import dev.liftgate.org.User
import dev.liftgate.testConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
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
 * @date 9/18/2026
 */
class AuthRoutesTest {
    private val user = User(UUID.randomUUID(), "dean", null, null, null)
    private var verifier: String? = null
    private val provider = HttpClient(MockEngine { request ->
        val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        when (request.url.encodedPath) {
            "/login/oauth/access_token" -> {
                verifier = (request.body as FormDataContent).formData["code_verifier"]
                respond("""{"access_token":"ghu_token"}""", HttpStatusCode.OK, headers)
            }
            "/user" -> respond("""{"id":7,"login":"dean"}""", HttpStatusCode.OK, headers)
            else -> respond("{}", HttpStatusCode.Forbidden, headers)
        }
    }) { install(ContentNegotiation) { json(json) } }
    private val oauth = OAuth(
        provider,
        "http://localhost:8080",
        listOf(OAuthProviders.github(GitHubConfig("1", "pem", "webhook", "client", "secret")), OAuthProviders.google(OAuthClient("client", "secret"))),
    )
    private val signIn = mockk<SignIn>()
    private val gitConnections = mockk<GitConnections>()
    private val passkeys = mockk<Passkeys>()
    private val app = mockk<App>().also {
        every { it.config } returns testConfig()
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.oauth } returns oauth
        every { it.signIn } returns signIn
        every { it.gitConnections } returns gitConnections
        every { it.passkeys } returns passkeys
        every { it.emailCodes } returns null
        every { it.sessions } returns mockk<Sessions> { coEvery { resolve("s") } returns user }
    }

    private fun ApplicationTestBuilder.browser() = createClient {
        followRedirects = false
        install(HttpCookies)
    }

    @Test
    fun `next must be a local path`() {
        assertEquals("/acme/shop", safeNext("/acme/shop"))
        listOf(null, "", "https://evil.example", "//evil.example", "/\\evil.example", "acme").forEach { assertNull(safeNext(it), it) }
    }

    @Test
    fun `providers lists the configured oauth providers`() = testApplication {
        application { liftgate(app) }
        assertEquals(
            """{"oauth":["github","google"],"passkey":true,"email":false,"sso":true}""",
            client.get("/api/v1/auth/providers").bodyAsText(),
        )
    }

    @Test
    fun `github sign-in completes with pkce, stores the git connection and returns to next`() = testApplication {
        val identity = VerifiedIdentity("github", "7", null, false, "dean")
        coEvery { signIn.complete(identity) } returns SignedIn(user.id, "session-1")
        coEvery { gitConnections.store(user.id, "dean", any()) } just Runs
        application { liftgate(app) }
        val browser = browser()
        val login = browser.get("/api/v1/auth/github/login?next=/acme")
        assertEquals(HttpStatusCode.Found, login.status)
        val authorize = Url(login.headers[HttpHeaders.Location]!!)
        val callback = browser.get("/api/v1/auth/github/callback?code=c&state=${authorize.parameters["state"]}")
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("http://localhost:3000/acme", callback.headers[HttpHeaders.Location])
        assertEquals(authorize.parameters["code_challenge"], pkceChallenge(verifier!!))
        assertTrue(callback.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$SESSION_COOKIE=session-1") })
        coVerify { gitConnections.store(user.id, "dean", OAuthTokens("ghu_token", null, null)) }
    }

    @Test
    fun `a callback without the matching state cookie returns to login with the error`() = testApplication {
        application { liftgate(app) }
        val browser = browser()
        browser.get("/api/v1/auth/github/login")
        val response = browser.get("/api/v1/auth/github/callback?code=c&state=forged")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("http://localhost:3000/login?error=invalid_state", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `denied consent returns to login with access_denied`() = testApplication {
        application { liftgate(app) }
        val browser = browser()
        val state = Url(browser.get("/api/v1/auth/google/login").headers[HttpHeaders.Location]!!).parameters["state"]
        val response = browser.get("/api/v1/auth/google/callback?error=access_denied&state=$state")
        assertEquals("http://localhost:3000/login?error=access_denied", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `linking an identity owned by someone else returns to next with the error`() = testApplication {
        coEvery { signIn.link(user.id, any()) } throws LiftgateException(HttpStatusCode.Conflict, "identity_in_use", "taken")
        application { liftgate(app) }
        val browser = browser()
        val login = browser.get("/api/v1/auth/github/login?intent=link&next=/account") { cookie(SESSION_COOKIE, "s") }
        val state = Url(login.headers[HttpHeaders.Location]!!).parameters["state"]
        val response = browser.get("/api/v1/auth/github/callback?code=c&state=$state") { cookie(SESSION_COOKIE, "s") }
        assertEquals("http://localhost:3000/account?error=identity_in_use", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `linking needs a session and connecting is github only`() = testApplication {
        application { liftgate(app) }
        val browser = browser()
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/api/v1/auth/google/login?intent=link").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, browser.get("/api/v1/auth/google/login?intent=connect") { cookie(SESSION_COOKIE, "s") }.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, browser.get("/api/v1/auth/github/login?intent=admin").status)
        assertEquals(HttpStatusCode.NotFound, browser.get("/api/v1/auth/myspace/login").status)
        assertEquals(HttpStatusCode.Found, browser.get("/api/v1/auth/github/login?intent=connect") { cookie(SESSION_COOKIE, "s") }.status)
    }

    @Test
    fun `passkey sign-in returns the challenge cookie and starts a session`() = testApplication {
        every { passkeys.assertionOptions() } returns ("challenge-1" to """{"publicKey":{}}""")
        coEvery { passkeys.verify("challenge-1", """{"id":"c"}""") } returns SignedIn(user.id, "session-2")
        application { liftgate(app) }
        val browser = browser()
        val options = browser.post("/api/v1/auth/passkey/options")
        assertTrue(options.headers[HttpHeaders.SetCookie]!!.contains("HttpOnly", ignoreCase = true))
        val verify = browser.post("/api/v1/auth/passkey/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"credential":{"id":"c"}}""")
        }
        assertEquals(HttpStatusCode.NoContent, verify.status)
        assertTrue(verify.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$SESSION_COOKIE=session-2") })
    }

    @Test
    fun `removing the last sign-in method answers 409`() = testApplication {
        val id = UUID.randomUUID()
        coEvery { signIn.unlink(user.id, id) } throws LiftgateException(HttpStatusCode.Conflict, "last_method", "add another sign-in method before removing this one")
        application { liftgate(app) }
        val response = client.delete("/api/v1/me/identities/$id") { cookie(SESSION_COOKIE, "s") }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("last_method", json.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).error)
    }
}
