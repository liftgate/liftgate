package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.GitConnections
import dev.liftgate.auth.Sessions
import dev.liftgate.build.Detection
import dev.liftgate.build.GitHubApp
import dev.liftgate.build.TestKeys
import dev.liftgate.cache.Cache
import dev.liftgate.config.GitHubConfig
import dev.liftgate.org.User
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
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
import java.util.Collections
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/27/2026
 */
class GitHubRoutesTest {
    companion object {
        private val cache by lazy { Cache(testConfig()) }
    }

    private val user = User(UUID.randomUUID(), "dean", null, null, null)
    private val sha = UUID.randomUUID().toString().replace("-", "")
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
    private var blobs = """{"data":{"repository":{"p0":{"text":"{\"dependencies\":{\"next\":\"16\"}}","byteSize":30},"p1":{"text":"DATABASE_URL=postgres://u:p@h/db","byteSize":33}}}}"""
    private val github = GitHubApp(
        GitHubConfig("12345", TestKeys.privateKeyPem),
        HttpClient(MockEngine { request ->
            requests += request
            val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            fun ok(body: String) = respond(body, HttpStatusCode.OK, headers)
            when {
                request.headers[HttpHeaders.Authorization] == "Bearer ghu_revoked" -> respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized, headers)
                request.url.encodedPath == "/app" -> ok("""{"html_url":"https://github.com/apps/liftgate"}""")
                request.url.encodedPath == "/user/installations" -> ok("""{"installations":[{"id":42}]}""")
                request.url.encodedPath in setOf("/repos/acme/shop", "/repos/acme/broken") -> ok("""{"default_branch":"main","permissions":{"push":true}}""")
                request.url.encodedPath == "/repos/acme/docs" -> ok("""{"default_branch":"main","permissions":{"push":false}}""")
                request.url.encodedPath.endsWith("/installation") -> ok("""{"id":42}""")
                request.url.encodedPath == "/app/installations/42/access_tokens" -> respond("""{"token":"ghs_token"}""", HttpStatusCode.Created, headers)
                request.url.encodedPath == "/repos/acme/shop/commits/main" -> ok("""{"sha":"$sha","commit":{"message":"ship it\n\nwith a body","tree":{"sha":"t1"}}}""")
                request.url.encodedPath == "/repos/acme/shop/git/trees/t1" -> ok("""{"tree":[{"path":".env.example","type":"blob"},{"path":"package.json","type":"blob"}]}""")
                request.url.encodedPath == "/graphql" -> ok(blobs)
                request.url.encodedPath.startsWith("/repos/acme/broken/") -> respond("""{"message":"Server Error"}""", HttpStatusCode.InternalServerError, headers)
                else -> ok(
                    """{"repositories":[{"full_name":"acme/shop","default_branch":"main","private":true,"permissions":{"push":true}},{"full_name":"acme/docs","default_branch":"main","permissions":{"push":false}}]}""",
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

    private suspend fun ApplicationTestBuilder.detect(repo: String) = client.get("/api/v1/me/github/repositories/detect?repo=$repo") { session() }

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

    @Test
    fun `detects the default branch of a repository the user can push to and reads each commit once`() = testApplication {
        every { app.cache } returns cache
        application { liftgate(app) }
        val first = detect("acme/shop")
        assertEquals(HttpStatusCode.OK, first.status)
        val detection = json.decodeFromString(Detection.serializer(), first.bodyAsText())
        assertEquals(Triple("main", Detection.Commit(sha, "ship it"), false), Triple(detection.ref, detection.commit, detection.partial))
        assertEquals("next" to listOf("DATABASE_URL"), detection.services.single().let { it.framework?.id to it.variables.map { variable -> variable.name } })
        assertTrue("u:p@" !in first.bodyAsText())
        assertEquals(detection, json.decodeFromString(Detection.serializer(), detect("acme/shop").bodyAsText()))
        assertEquals(listOf(1, 1), listOf("/repos/acme/shop/git/trees/t1", "/graphql").map { path -> requests.count { it.url.encodedPath == path } })
    }

    @Test
    fun `detection needs a github connection and push access`() = testApplication {
        every { app.cache } returns cache
        application { liftgate(app) }
        coEvery { connections.github(user.id) } returns null
        assertEquals("github_not_connected", json.decodeFromString(ErrorBody.serializer(), detect("acme/shop").bodyAsText()).error)
        coEvery { connections.github(user.id) } returns ("ghu_dean" to "dean")
        val refused = detect("acme/docs")
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("repo", json.decodeFromString(ErrorBody.serializer(), refused.bodyAsText()).field)
        assertEquals(HttpStatusCode.UnprocessableEntity, detect("not-a-repo").status)
    }

    @Test
    fun `detection is limited to 30 calls a minute per user`() = testApplication {
        every { app.cache } returns cache
        application { liftgate(app) }
        val statuses = (1..61).map { detect("acme/shop").status }
        assertEquals(HttpStatusCode.OK, statuses.first())
        assertTrue(HttpStatusCode.TooManyRequests in statuses)
    }

    @Test
    fun `a github failure or a graphql error answers 200 with a partial detection, a warning and nothing cached`() = testApplication {
        every { app.cache } returns cache
        application { liftgate(app) }
        blobs = """{"data":null,"errors":[{"message":"Something went wrong while executing your query."}]}"""
        listOf("acme/broken", "acme/shop").forEach { repo ->
            val response = detect(repo)
            assertEquals(HttpStatusCode.OK, response.status)
            val detection = json.decodeFromString(Detection.serializer(), response.bodyAsText())
            assertEquals(Triple(true, emptyList(), listOf("Couldn't read $repo, so nothing was detected. Railpack will still detect the stack during the build.")), Triple(detection.partial, detection.services, detection.warnings))
        }
        assertNull(cache.detections["acme/shop@$sha"])
    }
}
