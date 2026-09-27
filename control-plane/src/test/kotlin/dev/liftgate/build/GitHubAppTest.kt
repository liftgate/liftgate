package dev.liftgate.build

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.liftgate.config.GitHubConfig
import dev.liftgate.http.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class GitHubAppTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun app(privateKey: String = TestKeys.privateKeyPem) = GitHubApp(
        GitHubConfig("12345", privateKey),
        HttpClient(MockEngine { request ->
            requests += request
            val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when (request.url.encodedPath) {
                "/app/installations/42/access_tokens" -> respond("""{"token":"ghs_token","expires_at":"2026-09-17T12:00:00Z"}""", HttpStatusCode.Created, headers)
                "/repos/acme/shop" -> respond("""{"permissions":{"push":true}}""", HttpStatusCode.OK, headers)
                "/repos/acme/docs" -> respond("""{"permissions":{"push":false}}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/installation" -> respond("""{"id":42}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/commits/main" -> respond("""{"sha":"abc123","commit":{"message":"ship it"}}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/collaborators/owner/permission" -> respond("""{"permission":"admin","role_name":"admin"}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/collaborators/maintainer/permission" -> respond("""{"permission":"write","role_name":"maintain"}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/collaborators/triager/permission" -> respond("""{"permission":"read","role_name":"triage"}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/collaborators/flaky/permission" -> respond("""{"message":"Bad Gateway"}""", HttpStatusCode.BadGateway, headers)
                else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, headers)
            }
        }) { install(ContentNegotiation) { json(json) } },
    )

    @Test
    fun `app jwt is signed by the app key, issued by the app id and short lived`() {
        val jwt = JWT.require(Algorithm.RSA256(TestKeys.pair.public as RSAPublicKey, null)).withIssuer("12345").build().verify(app().appJwt())
        assertTrue(jwt.expiresAtAsInstant <= Instant.now().plusSeconds(600))
        assertTrue(jwt.issuedAtAsInstant < Instant.now())
    }

    @Test
    fun `private keys with escaped newlines are accepted and garbage is rejected`() {
        app(TestKeys.privateKeyPem.replace("\n", "\\n")).appJwt()
        assertFailsWith<IllegalStateException> { app("not a key") }
    }

    @Test
    fun `installation token is minted with the app jwt for one repository with read-only contents`() = runBlocking {
        assertEquals("ghs_token", app().installationToken(42, "shop"))
        val request = requests.single()
        assertEquals("12345", JWT.decode(request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ")).issuer)
        assertEquals(
            json.parseToJsonElement("""{"repositories":["shop"],"permissions":{"contents":"read","metadata":"read"}}"""),
            json.parseToJsonElement((request.body as TextContent).text),
        )
    }

    @Test
    fun `branch head is read with a token scoped to that repository`() = runBlocking {
        assertEquals("abc123" to "ship it", app().branchHead(42, "acme/shop", "main"))
        assertTrue(""""repositories":["shop"]""" in (requests.first().body as TextContent).text)
        assertEquals("Bearer ghs_token", requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `only admin, maintain and write collaborators can push`() = runBlocking {
        assertTrue(app().canPush("ghs_token", "acme/shop", "owner"))
        assertTrue(app().canPush("ghs_token", "acme/shop", "maintainer"))
        assertFalse(app().canPush("ghs_token", "acme/shop", "triager"))
        assertFalse(app().canPush("ghs_token", "acme/shop", "stranger"))
        assertEquals("Bearer ghs_token", requests.last().headers[HttpHeaders.Authorization])
        assertFailsWith<ServerResponseException> { app().canPush("ghs_token", "acme/shop", "flaky") }
        Unit
    }

    @Test
    fun `an installation is bound only to a repository the user can push to`() = runBlocking {
        assertEquals(42, app().installation("ghu_user", "acme/shop"))
        assertEquals("Bearer ghu_user", requests.first().headers[HttpHeaders.Authorization])
        assertNull(app().installation("ghu_user", "acme/docs"))
        assertNull(app().installation("ghu_user", "rival/private"))
    }

    @Test
    fun `github errors surface as exceptions`() {
        assertFailsWith<ClientRequestException> { runBlocking { app().branchHead(42, "acme/shop", "missing") } }
    }
}
