package dev.liftgate.auth

import dev.liftgate.config.GitHubConfig
import dev.liftgate.http.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * @author Dean
 * @date 9/18/2026
 */
class GitHubOAuthTest {
    private fun oauth(user: String, emails: Pair<HttpStatusCode, String>) = GitHubOAuth(
        GitHubConfig("1", "pem", "webhook", "client", "secret"),
        HttpClient(MockEngine { request ->
            val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when (request.url.encodedPath) {
                "/login/oauth/access_token" -> respond("""{"access_token":"ghu_token"}""", HttpStatusCode.OK, headers)
                "/user" -> respond(user, HttpStatusCode.OK, headers)
                "/user/emails" -> respond(emails.second, emails.first, headers)
                else -> respond("{}", HttpStatusCode.NotFound, headers)
            }
        }) { install(ContentNegotiation) { json(json) } },
        "https://liftgate.dev/api/v1/auth/github/callback",
    )

    @Test
    fun `github app without email permission signs in without an email`() = runBlocking {
        val (user, token) = oauth(
            """{"id":7,"login":"dean"}""",
            HttpStatusCode.Forbidden to """{"message":"Resource not accessible by integration"}""",
        ).exchange("code")
        assertEquals("dean", user.login)
        assertNull(user.email)
        assertEquals("ghu_token", token)
    }

    @Test
    fun `primary email is used when the profile hides it`() = runBlocking {
        val (user) = oauth(
            """{"id":7,"login":"dean"}""",
            HttpStatusCode.OK to """[{"email":"old@x.dev","primary":false},{"email":"dean@x.dev","primary":true}]""",
        ).exchange("code")
        assertEquals("dean@x.dev", user.email)
    }

    @Test
    fun `public profile email wins without a second request`() = runBlocking {
        val (user) = oauth(
            """{"id":7,"login":"dean","email":"public@x.dev"}""",
            HttpStatusCode.Forbidden to "{}",
        ).exchange("code")
        assertEquals("public@x.dev", user.email)
    }
}
