package dev.liftgate.http

import com.auth0.jwt.JWT
import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.build.BuildJobs
import dev.liftgate.build.RegistryTokens
import dev.liftgate.build.TestRegistry
import dev.liftgate.deploy.Builds
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.EnvironmentKind
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
    private val config = TestRegistry.config
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
        parameter("service", TestRegistry.ADDRESS)
        scopes.forEach { parameter("scope", it) }
    }

    private suspend fun ApplicationTestBuilder.jwt(account: String, password: String, vararg scopes: String): String {
        val response = token(account, password, *scopes)
        assertEquals(HttpStatusCode.OK, response.status)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content
    }

    private fun access(token: String) = JWT.decode(token).getClaim("access").asList(Map::class.java)

    private fun registry(method: String, path: String, token: String): Int = TestRegistry.send(method, "/v2/$path", token).statusCode()

    @Test
    fun `a build token reaches only its own repository on a token-auth distribution`() = testApplication {
        val acme = runningBuild("acme")
        val rival = runningBuild("rival")
        val acmePassword = tokens.issue(acme)
        val rivalPassword = tokens.issue(rival)
        application { liftgate(app) }

        val own = jwt(BuildJobs.name(acme), acmePassword, "repository:acme/shop/production/api:pull,push", "repository:rival/shop/production/api:pull,push")
        assertEquals(listOf(mapOf("type" to "repository", "name" to "acme/shop/production/api", "actions" to listOf("pull", "push"))), access(own))
        assertTrue(JWT.decode(own).expiresAtAsInstant <= Instant.now().plusSeconds(300))
        assertEquals(202, registry("POST", "acme/shop/production/api/blobs/uploads/", own))

        val theirs = jwt(BuildJobs.name(rival), rivalPassword, "repository:rival/shop/production/api:pull,push")
        assertEquals(202, registry("POST", "rival/shop/production/api/blobs/uploads/", theirs))
        assertEquals(404, registry("GET", "rival/shop/production/api/tags/list", theirs))
        assertEquals(401, registry("POST", "rival/shop/production/api/blobs/uploads/", own))
        assertEquals(401, registry("GET", "rival/shop/production/api/tags/list", own))
    }

    @Test
    fun `a preview build may pull its service's production repository and push only to its own`() = testApplication {
        val projects = Projects(db)
        val project = projects.create(Orgs(db).create("acme", "acme", db.tx { insertUser("acme", null, null, null) }.id).id, "shop", "Shop", "acme/shop", 42)
        services.create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
        val preview = projects.createEnvironment(project.id, "preview", "Preview", EnvironmentKind.PREVIEW, "develop")
        val build = builds.request(services.create(preview.id, ServiceSpec("api", "API", ServiceKind.WEB)).id, "abc123", null, "develop").id.also { builds.markRunning(it) }
        val password = tokens.issue(build)
        application { liftgate(app) }

        val granted = jwt(
            BuildJobs.name(build), password,
            "repository:acme/shop/preview/api:pull,push", "repository:acme/shop/production/api:pull,push", "repository:acme/shop/production/web:pull",
        )
        assertEquals(
            listOf(
                mapOf("type" to "repository", "name" to "acme/shop/preview/api", "actions" to listOf("pull", "push")),
                mapOf("type" to "repository", "name" to "acme/shop/production/api", "actions" to listOf("pull")),
            ),
            access(granted),
        )
        assertEquals(404, registry("GET", "acme/shop/production/api/tags/list", granted))
        assertEquals(401, registry("POST", "acme/shop/production/api/blobs/uploads/", granted))
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
            token(BuildJobs.name(finished), finishedPassword, "repository:acme/shop/production/api:pull"),
            token(BuildJobs.name(revoked), revokedPassword, "repository:rival/shop/production/api:pull"),
            token(BuildJobs.name(revoked), finishedPassword, "repository:rival/shop/production/api:pull"),
            token("pull", "wrong", "repository:acme/shop/production/api:pull"),
            token("janitor", "pull-password", "repository:acme/shop/production/api:delete"),
            client.get("/api/v1/registry/token?scope=repository:acme/shop/production/api:pull"),
        ).forEach { assertEquals(HttpStatusCode.Unauthorized, it.status) }
    }

    @Test
    fun `the node pull account reads any repository and writes none`() = testApplication {
        application { liftgate(app) }
        val pull = jwt("pull", "pull-password", "repository:acme/shop/production/api:pull,push")
        assertEquals(listOf(mapOf("type" to "repository", "name" to "acme/shop/production/api", "actions" to listOf("pull"))), access(pull))
        assertEquals(404, registry("GET", "acme/shop/production/api/tags/list", pull))
        assertEquals(401, registry("POST", "acme/shop/production/api/blobs/uploads/", pull))
    }

    @Test
    fun `the janitor account may pull and delete in any repository and push to none`() = testApplication {
        application { liftgate(app) }
        val janitor = jwt("janitor", "janitor-password", "repository:acme/shop/production/api:pull,push,delete")
        assertEquals(listOf(mapOf("type" to "repository", "name" to "acme/shop/production/api", "actions" to listOf("pull", "delete"))), access(janitor))
        assertEquals(401, registry("POST", "acme/shop/production/api/blobs/uploads/", janitor))
    }

    @Test
    fun `shared registry auth serves no tokens`() = testApplication {
        every { app.config } returns testConfig()
        application { liftgate(app) }
        assertEquals(HttpStatusCode.NotFound, token("pull", "pull-password", "repository:acme/shop/production/api:pull").status)
    }
}
