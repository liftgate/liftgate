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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                "/repos/acme/shop" -> respond("""{"default_branch":"master","permissions":{"push":true}}""", HttpStatusCode.OK, headers)
                "/repos/acme/docs" -> respond("""{"permissions":{"push":false}}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/installation" -> respond("""{"id":42}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/commits/main" -> respond("""{"sha":"abc123","commit":{"message":"ship it","tree":{"sha":"t1"}}}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/git/trees/t1" -> respond(
                    """{"sha":"t1","tree":[{"path":"package.json","type":"blob"},{"path":"apps","type":"tree"},{"path":"apps/web/package.json","type":"blob"},{"path":"lib","type":"commit"}],"truncated":true}""",
                    HttpStatusCode.OK,
                    headers,
                )
                "/graphql" -> respond(
                    """{"data":{"repository":{"p0":{"text":"{}","byteSize":2,"isBinary":false},"p1":null,"p2":{"text":null,"byteSize":10,"isBinary":true},"p3":{"text":"x","byteSize":65537,"isBinary":false}}}}""",
                    HttpStatusCode.OK,
                    headers,
                )
                "/repos/acme/shop/collaborators/owner/permission" -> respond("""{"permission":"admin","role_name":"admin"}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/collaborators/maintainer/permission" -> respond("""{"permission":"write","role_name":"maintain"}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/collaborators/triager/permission" -> respond("""{"permission":"read","role_name":"triage"}""", HttpStatusCode.OK, headers)
                "/repos/acme/shop/collaborators/flaky/permission" -> respond("""{"message":"Bad Gateway"}""", HttpStatusCode.BadGateway, headers)
                "/user/installations" -> respond("""{"total_count":2,"installations":[{"id":42},{"id":7}]}""", HttpStatusCode.OK, headers)
                "/user/installations/42/repositories" -> respond(
                    if (request.url.parameters["page"] == "1") """{"repositories":[${(1..100).joinToString(",") { repo("acme/r$it", true, "2026-09-01T00:00:00Z") }}]}"""
                    else """{"repositories":[${repo("acme/docs", false, "2026-09-29T00:00:00Z")},${repo("acme/shop", true, "2026-09-28T00:00:00Z")}]}""",
                    HttpStatusCode.OK, headers,
                )
                "/user/installations/7/repositories" -> respond("""{"repositories":[${repo("dean/blog", true, "2026-09-02T00:00:00Z", hidden = true)}]}""", HttpStatusCode.OK, headers)
                "/app" -> respond("""{"slug":"liftgate","html_url":"https://github.com/apps/liftgate"}""", HttpStatusCode.OK, headers)
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
    fun `an installation is bound only to a repository the user can push to, with its default branch`() = runBlocking {
        assertEquals(42L to "master", app().installation("ghu_user", "acme/shop"))
        assertEquals("Bearer ghu_user", requests.first().headers[HttpHeaders.Authorization])
        assertNull(app().installation("ghu_user", "acme/docs"))
        assertNull(app().installation("ghu_user", "rival/private"))
    }

    private fun repo(name: String, push: Boolean, pushedAt: String, hidden: Boolean = false) =
        """{"full_name":"$name","default_branch":"main","private":$hidden,"pushed_at":"$pushedAt","permissions":{"admin":false,"push":$push,"pull":true}}"""

    @Test
    fun `importable lists every page of every installation the user can push to, newest push first, with a cached install link`() = runBlocking {
        val app = app()
        val importable = app.importable("ghu_user")
        assertEquals(listOf("acme/shop", "dean/blog", "acme/r1"), importable.repositories.take(3).map { it.fullName })
        assertEquals(102, importable.repositories.size)
        assertFalse(importable.repositories.any { it.fullName == "acme/docs" })
        assertEquals(GitHubApp.Importable.Repo("dean/blog", "main", true), importable.repositories[1])
        assertEquals("https://github.com/apps/liftgate/installations/new", importable.installUrl)
        assertEquals("https://github.com/apps/liftgate/installations/new", app.importable("ghu_user").installUrl)
        assertEquals(1, requests.count { it.url.encodedPath == "/app" })
        assertEquals("12345", JWT.decode(requests.first { it.url.encodedPath == "/app" }.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ")).issuer)
        assertTrue(requests.filter { it.url.encodedPath.startsWith("/user/") }.all { it.headers[HttpHeaders.Authorization] == "Bearer ghu_user" && it.url.parameters["per_page"] == "100" })
        assertEquals(listOf("1", "2"), requests.filter { it.url.encodedPath == "/user/installations/42/repositories" }.take(2).map { it.url.parameters["page"] })
    }

    @Test
    fun `commit, tree and files read one repository with the scoped token and pass graphql expressions as variables`() = runBlocking {
        val app = app()
        assertEquals(GitHubApp.Head("abc123", "ship it", "t1"), app.commit("ghs_token", "acme/shop", "main"))
        assertEquals(listOf("package.json", "apps/web/package.json") to true, app.tree("ghs_token", "acme/shop", "t1"))
        assertEquals("1", requests.last().url.parameters["recursive"])
        val paths = listOf("package.json", "missing.json", "logo.png", "big.json")
        assertEquals(mapOf("package.json" to "{}"), app.files("ghs_token", "acme/shop", "abc123", paths))
        val body = json.parseToJsonElement((requests.last().body as TextContent).text).jsonObject
        val query = body.getValue("query").jsonPrimitive.content
        assertTrue(paths.none { it in query } && "abc123" !in query, query)
        assertEquals(
            json.parseToJsonElement("""{"owner":"acme","name":"shop","p0":"abc123:package.json","p1":"abc123:missing.json","p2":"abc123:logo.png","p3":"abc123:big.json"}"""),
            body.getValue("variables"),
        )
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer ghs_token" })
        assertEquals(emptyMap<String, String>(), app.files("ghs_token", "acme/shop", "abc123", emptyList()))
        assertEquals(3, requests.size)
    }

    @Test
    fun `github errors surface as exceptions`() {
        assertFailsWith<ClientRequestException> { runBlocking { app().branchHead(42, "acme/shop", "missing") } }
    }
}
