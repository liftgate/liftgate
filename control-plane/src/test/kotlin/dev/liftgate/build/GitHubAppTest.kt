package dev.liftgate.build

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.liftgate.config.GitHubConfig
import dev.liftgate.http.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class GitHubAppTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n"
    private val requests = mutableListOf<HttpRequestData>()

    private fun app(privateKey: String = pem) = GitHubApp(
        GitHubConfig("12345", privateKey, "webhook", "client", "client-secret"),
        HttpClient(MockEngine { request ->
            requests += request
            val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when (request.url.encodedPath) {
                "/app/installations/42/access_tokens" -> respond("""{"token":"ghs_token","expires_at":"2026-09-17T12:00:00Z"}""", HttpStatusCode.Created, headers)
                "/repos/acme/shop" -> respond("""{"permissions":{"push":true}}""", HttpStatusCode.OK, headers)
                "/repos/acme/docs" -> respond("""{"permissions":{"push":false}}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/installation" -> respond("""{"id":42}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/commits/main" -> respond("""{"sha":"abc123","commit":{"message":"ship it"}}""", HttpStatusCode.OK, headers)
                else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, headers)
            }
        }) { install(ContentNegotiation) { json(json) } },
    )

    @Test
    fun `app jwt is signed by the app key, issued by the app id and short lived`() {
        val jwt = JWT.require(Algorithm.RSA256(keys.public as RSAPublicKey, null)).withIssuer("12345").build().verify(app().appJwt())
        assertTrue(jwt.expiresAtAsInstant <= Instant.now().plusSeconds(600))
        assertTrue(jwt.issuedAtAsInstant < Instant.now())
    }

    @Test
    fun `private keys with escaped newlines are accepted and garbage is rejected`() {
        app(pem.replace("\n", "\\n")).appJwt()
        assertFailsWith<IllegalStateException> { app("not a key") }
    }

    @Test
    fun `installation token is minted with the app jwt`() = runBlocking {
        assertEquals("ghs_token", app().installationToken(42))
        val authorization = requests.single().headers[HttpHeaders.Authorization].orEmpty()
        assertEquals("12345", JWT.decode(authorization.removePrefix("Bearer ")).issuer)
    }

    @Test
    fun `branch head is read with the installation token`() = runBlocking {
        assertEquals("abc123" to "ship it", app().branchHead(42, "acme/shop", "main"))
        assertEquals("Bearer ghs_token", requests.last().headers[HttpHeaders.Authorization])
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
