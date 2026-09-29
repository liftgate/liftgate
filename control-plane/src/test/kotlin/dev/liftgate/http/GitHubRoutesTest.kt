package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.GitConnections
import dev.liftgate.auth.Sessions
import dev.liftgate.build.GitHubApp
import dev.liftgate.build.TestKeys
import dev.liftgate.config.GitHubConfig
import dev.liftgate.org.User
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Dean
 * @date 9/27/2026
 */
class GitHubRoutesTest {
    private val user = User(UUID.randomUUID(), "dean", null, null, null)
    private val github = GitHubApp(
        GitHubConfig("12345", TestKeys.privateKeyPem),
        HttpClient(MockEngine { request ->
            val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when {
                request.headers[HttpHeaders.Authorization] == "Bearer ghu_revoked" -> respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized, headers)
                request.url.encodedPath == "/app" -> respond("""{"html_url":"https://github.com/apps/liftgate"}""", HttpStatusCode.OK, headers)
                request.url.encodedPath == "/user/installations" -> respond("""{"installations":[{"id":42}]}""", HttpStatusCode.OK, headers)
                else -> respond(
                    """{"repositories":[{"full_name":"acme/shop","default_branch":"main","private":true,"permissions":{"push":true}},{"full_name":"acme/docs","default_branch":"main","permissions":{"push":false}}]}""",
                    HttpStatusCode.OK,
                    headers,
                )
            }
        }) { install(ContentNegotiation) { json(json) } },
    )
    private val connections = mockk<GitConnections> { coEvery { github(user.id) } returns ("ghu_dean" to "dean") }
    private val app = mockk<App> {
        every { config } returns testConfig()
        every { cache } returns unlimitedCache
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { sessions } returns mockk<Sessions> { coEvery { resolve("s") } returns user }
        every { gitConnections } returns connections
        every { github } returns this@GitHubRoutesTest.github
    }

    private suspend fun ApplicationTestBuilder.repositories() = client.get("/api/v1/me/github/repositories") { session() }

    @Test
    fun `lists the repositories the signed-in user can push to, with the install link`() = testApplication {
        application { liftgate(app) }
        val response = repositories()
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            """{"repositories":[{"fullName":"acme/shop","defaultBranch":"main","private":true}],"installUrl":"https://github.com/apps/liftgate/installations/new"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `no connection and a revoked token both ask the user to connect github`() = testApplication {
        application { liftgate(app) }
        listOf(null, "ghu_revoked" to "dean").forEach {
            coEvery { connections.github(user.id) } returns it
            val response = repositories()
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("github_not_connected", json.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).error)
        }
    }

    @Test
    fun `answers 409 while the GitHub App is not configured`() = testApplication {
        every { app.github } returns null
        application { liftgate(app) }
        assertEquals("""{"error":"conflict","message":"the GitHub App is not configured"}""", repositories().bodyAsText())
    }
}
