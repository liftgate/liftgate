package dev.liftgate.http

import com.auth0.jwt.JWT
import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.build.BuildJobs
import dev.liftgate.build.RegistryTokens
import dev.liftgate.build.TestKeys
import dev.liftgate.config.RegistryTokenConfig
import dev.liftgate.deploy.Builds
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/27/2026
 */
class RegistryRoutesTest {
    private val db = TestDatabase.clean()
    private val config = testConfig().copy(
        registry = "10.200.0.1:5050",
        registryTokenAuth = true,
        registryTokens = RegistryTokenConfig(TestKeys.privateKeyPem, TestKeys.certificatePem, "pull-password"),
    )
    private val services = Services(db)
    private val builds = Builds(db)
    private val tokens = RegistryTokens(db, services, config)
    private val app = mockk<App> {
        every { config } returns this@RegistryRoutesTest.config
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { registryTokens } returns tokens
    }

    private suspend fun runningBuild(org: String): UUID {
        val projects = Projects(db)
        val project = projects.create(Orgs(db).create(org, org, db.tx { insertUser(org, null, null, null) }.id).id, "shop", "Shop", "$org/shop", 42)
        val service = services.create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
        return builds.request(service.id, "abc123", null, "main").id.also { builds.markRunning(it) }
    }

    private suspend fun ApplicationTestBuilder.token(account: String, password: String, vararg scopes: String) = client.get("/api/v1/registry/token") {
        basicAuth(account, password)
        parameter("service", "10.200.0.1:5050")
        scopes.forEach { parameter("scope", it) }
    }

    private suspend fun ApplicationTestBuilder.jwt(account: String, password: String, vararg scopes: String): String {
        val response = token(account, password, *scopes)
        assertEquals(HttpStatusCode.OK, response.status)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content
    }

    private fun access(token: String) = JWT.decode(token).getClaim("access").asList(Map::class.java)

    private fun registry(method: String, path: String, token: String): Int = http.send(
        HttpRequest.newBuilder(URI("http://${distribution.host}:${distribution.getMappedPort(5000)}/v2/$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .header("Authorization", "Bearer $token")
            .build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    @Test
    fun `a build token reaches only its own repository on a token-auth distribution`() = testApplication {
        val acme = runningBuild("acme")
        val rival = runningBuild("rival")
        val acmePassword = tokens.issue(acme)
        val rivalPassword = tokens.issue(rival)
        application { liftgate(app) }

        val own = jwt(BuildJobs.name(acme), acmePassword, "repository:acme/shop-api:pull,push", "repository:rival/shop-api:pull,push")
        assertEquals(listOf(mapOf("type" to "repository", "name" to "acme/shop-api", "actions" to listOf("pull", "push"))), access(own))
        assertTrue(JWT.decode(own).expiresAtAsInstant <= Instant.now().plusSeconds(300))
        assertEquals(202, registry("POST", "acme/shop-api/blobs/uploads/", own))

        val theirs = jwt(BuildJobs.name(rival), rivalPassword, "repository:rival/shop-api:pull,push")
        assertEquals(202, registry("POST", "rival/shop-api/blobs/uploads/", theirs))
        assertEquals(404, registry("GET", "rival/shop-api/tags/list", theirs))
        assertEquals(401, registry("POST", "rival/shop-api/blobs/uploads/", own))
        assertEquals(401, registry("GET", "rival/shop-api/tags/list", own))
    }

    @Test
    fun `finished builds, revoked logins, wrong passwords and anonymous requests get no token`() = testApplication {
        val finished = runningBuild("acme")
        val revoked = runningBuild("rival")
        val finishedPassword = tokens.issue(finished)
        val revokedPassword = tokens.issue(revoked)
        tokens.revoke(revoked)
        builds.markFailed(finished, "the build job failed")
        application { liftgate(app) }
        listOf(
            token(BuildJobs.name(finished), finishedPassword, "repository:acme/shop-api:pull"),
            token(BuildJobs.name(revoked), revokedPassword, "repository:rival/shop-api:pull"),
            token(BuildJobs.name(revoked), finishedPassword, "repository:rival/shop-api:pull"),
            token("pull", "wrong", "repository:acme/shop-api:pull"),
            client.get("/api/v1/registry/token?scope=repository:acme/shop-api:pull"),
        ).forEach { assertEquals(HttpStatusCode.Unauthorized, it.status) }
    }

    @Test
    fun `the node pull account reads any repository and writes none`() = testApplication {
        application { liftgate(app) }
        val pull = jwt("pull", "pull-password", "repository:acme/shop-api:pull,push")
        assertEquals(listOf(mapOf("type" to "repository", "name" to "acme/shop-api", "actions" to listOf("pull"))), access(pull))
        assertEquals(404, registry("GET", "acme/shop-api/tags/list", pull))
        assertEquals(401, registry("POST", "acme/shop-api/blobs/uploads/", pull))
    }

    @Test
    fun `shared registry auth serves no tokens`() = testApplication {
        every { app.config } returns testConfig()
        application { liftgate(app) }
        assertEquals(HttpStatusCode.NotFound, token("pull", "pull-password", "repository:acme/shop-api:pull").status)
    }

    companion object {
        private val http = HttpClient.newHttpClient()
        private val distribution = GenericContainer<Nothing>(DockerImageName.parse("registry:2.8.3")).apply {
            withCopyToContainer(Transferable.of(File("../infra/registry/config.yml").readText()), "/etc/docker/registry/config.yml")
            withCopyToContainer(Transferable.of(TestKeys.certificatePem), "/etc/docker/registry/token.crt")
            withEnv("REGISTRY_HTTP_ADDR", ":5000")
            addExposedPort(5000)
            start()
        }
    }
}
