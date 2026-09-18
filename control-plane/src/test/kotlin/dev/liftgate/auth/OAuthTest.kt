package dev.liftgate.auth

import dev.liftgate.config.GitHubConfig
import dev.liftgate.config.OAuthClient
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/18/2026
 */
class OAuthTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val github = OAuthProviders.github(GitHubConfig("1", "pem", "webhook", "client", "secret"))
    private val google = OAuthProviders.google(OAuthClient("client", "secret"))
    private val gitlab = OAuthProviders.gitlab(OAuthClient("client", "secret"), "https://git.example")
    private val bitbucket = OAuthProviders.bitbucket(OAuthClient("client", "secret"))

    private fun oauth(provider: OAuthProvider, vararg routes: Pair<String, Pair<HttpStatusCode, String>>) = OAuth(
        HttpClient(MockEngine { request ->
            requests += request
            val (status, body) = routes.toMap()[request.url.encodedPath] ?: (HttpStatusCode.NotFound to "{}")
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) { install(ContentNegotiation) { json(json) } },
        "https://liftgate.dev",
        listOf(provider),
    )

    private fun ok(body: String) = HttpStatusCode.OK to body

    private suspend fun OAuth.signIn(provider: OAuthProvider) = identity(provider, exchange(provider, "code", "verifier"))

    private fun tokenForm() = (requests.first().body as FormDataContent).formData

    private fun github(user: String, emails: Pair<HttpStatusCode, String>) = runBlocking {
        oauth(github, "/login/oauth/access_token" to ok("""{"access_token":"ghu_token"}"""), "/user" to ok(user), "/user/emails" to emails).signIn(github)
    }

    @Test
    fun `pkce challenge matches the rfc 7636 example`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `login url carries state, the s256 challenge and the registered callback`() {
        val url = Url(oauth(github).loginUrl(github, "state-1", "verifier"))
        assertEquals("https://github.com/login/oauth/authorize", url.toString().substringBefore('?'))
        assertEquals("https://liftgate.dev/api/v1/auth/github/callback", url.parameters["redirect_uri"])
        assertEquals("state-1", url.parameters["state"])
        assertEquals(pkceChallenge("verifier"), url.parameters["code_challenge"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals("client", url.parameters["client_id"])
    }

    @Test
    fun `code exchange posts the verifier and client credentials and keeps the refresh token`() = runBlocking {
        val tokens = oauth(github, "/login/oauth/access_token" to ok("""{"access_token":"a","refresh_token":"r","expires_in":28800}"""))
            .exchange(github, "code", "verifier")
        val form = tokenForm()
        assertEquals("verifier", form["code_verifier"])
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("secret", form["client_secret"])
        assertEquals("r", tokens.refreshToken)
        assertNotNull(tokens.expiresAt)
    }

    @Test
    fun `a token response without an access token fails the exchange and the refresh`() = runBlocking {
        val oauth = oauth(github, "/login/oauth/access_token" to ok("""{"error":"bad_verification_code"}"""))
        assertEquals("oauth_failed", assertFailsWith<LiftgateException> { oauth.exchange(github, "code", "verifier") }.code)
        assertNull(oauth.refresh(github, "r"))
    }

    @Test
    fun `github app without email permission signs in without an email`() {
        val identity = github("""{"id":7,"login":"dean"}""", HttpStatusCode.Forbidden to """{"message":"Resource not accessible by integration"}""")
        assertEquals(VerifiedIdentity(GITHUB, "7", null, false, "dean"), identity)
    }

    @Test
    fun `github primary verified email is trusted`() {
        val identity = github(
            """{"id":7,"login":"dean","email":"public@x.dev"}""",
            ok("""[{"email":"old@x.dev","primary":false,"verified":true},{"email":"dean@x.dev","primary":true,"verified":true}]"""),
        )
        assertEquals("dean@x.dev", identity.email)
        assertTrue(identity.emailVerified)
    }

    @Test
    fun `github public email is kept unverified when the email list is unreadable or unverified`() {
        listOf(HttpStatusCode.Forbidden to "{}", ok("""[{"email":"dean@x.dev","primary":true,"verified":false}]""")).forEach {
            val identity = github("""{"id":7,"login":"dean","email":"public@x.dev"}""", it)
            assertEquals("public@x.dev", identity.email)
            assertFalse(identity.emailVerified)
        }
    }

    @Test
    fun `google maps the openid userinfo`() = runBlocking {
        val identity = oauth(
            google,
            "/token" to ok("""{"access_token":"a"}"""),
            "/v1/userinfo" to ok("""{"sub":"g1","email":"dean@x.dev","email_verified":true,"name":"Dean","picture":"https://p"}"""),
        ).signIn(google)
        assertEquals(VerifiedIdentity("google", "g1", "dean@x.dev", true, null, "Dean", "https://p"), identity)
    }

    @Test
    fun `gitlab trusts the email of a confirmed account only`() = runBlocking {
        suspend fun gitlab(confirmedAt: String) = oauth(
            gitlab,
            "/oauth/token" to ok("""{"access_token":"a"}"""),
            "/api/v4/user" to ok("""{"id":3,"username":"dean","name":"Dean","email":"dean@x.dev","confirmed_at":$confirmedAt,"avatar_url":"https://a"}"""),
        ).signIn(gitlab)
        assertEquals(VerifiedIdentity("gitlab", "3", "dean@x.dev", true, "dean", "Dean", "https://a"), gitlab("\"2026-01-01T00:00:00Z\""))
        assertFalse(gitlab("null").emailVerified)
        assertTrue(Url(oauth(gitlab).loginUrl(gitlab, "s", "v")).toString().startsWith("https://git.example/oauth/authorize?"))
    }

    @Test
    fun `bitbucket authenticates with basic auth and trusts only a confirmed primary email`() = runBlocking {
        val identity = oauth(
            bitbucket,
            "/site/oauth2/access_token" to ok("""{"access_token":"a"}"""),
            "/2.0/user" to ok("""{"uuid":"{b1}","username":"dean","display_name":"Dean","links":{"avatar":{"href":"https://a"}}}"""),
            "/2.0/user/emails" to ok("""{"values":[{"email":"old@x.dev","is_primary":false,"is_confirmed":true},{"email":"dean@x.dev","is_primary":true,"is_confirmed":true}]}"""),
        ).signIn(bitbucket)
        assertEquals(VerifiedIdentity("bitbucket", "{b1}", "dean@x.dev", true, "dean", "Dean", "https://a"), identity)
        assertTrue(requests.first().headers[HttpHeaders.Authorization].orEmpty().startsWith("Basic "))
        assertNull(tokenForm()["client_secret"])
        assertNull(Url(oauth(bitbucket).loginUrl(bitbucket, "s", "v")).parameters["scope"])
    }
}
