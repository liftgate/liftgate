package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.Access
import dev.liftgate.auth.Sessions
import dev.liftgate.build.GitHubApp
import dev.liftgate.db.sql
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Builds
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.domain.Domains
import dev.liftgate.k8s.testBuild
import dev.liftgate.org.Organization
import dev.liftgate.org.User
import dev.liftgate.project.Environment
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.Project
import dev.liftgate.project.Projects
import dev.liftgate.service.BuildStrategy
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.discardingDb
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * @author Dean
 * @date 9/17/2026
 */
class ServiceRoutesTest {
    private val user = User(UUID.randomUUID(), "dean", null, null, null)
    private val org = Organization(UUID.randomUUID(), "acme", "Acme", "free")
    private val project = Project(UUID.randomUUID(), org.id, "shop", "Shop", "acme/shop", "master", 42)
    private val environment = Environment(UUID.randomUUID(), project.id, "production", "Production", EnvironmentKind.PRODUCTION, "master", "env-0123456789ab")
    private val service = Service(UUID.randomUUID(), environment.id, "api", "API", ServiceKind.WORKER, "/", BuildStrategy.AUTO, "Dockerfile", null, 1, 500, 512, null, null)
    private val services = mockk<Services>()
    private val access = mockk<Access>()
    private val projects = mockk<Projects> {
        coEvery { environment(environment.id) } returns environment
        coEvery { byId(project.id) } returns project
    }
    private val domains = mockk<Domains>()
    private val app = mockk<App>().also {
        every { it.services } returns services
        every { it.access } returns access
        every { it.projects } returns projects
        every { it.domains } returns domains
        every { it.sessions } returns mockk<Sessions> { coEvery { resolve("s") } returns user }
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.config } returns testConfig()
        every { it.cache } returns unlimitedCache
        every { it.db } returns discardingDb
    }

    init {
        coEvery { services.scope(any()) } returns null
        coEvery { services.scope(service.id) } returns ServiceScope(service, environment, project, org)
        coEvery { access.require(org.id, any(), any()) } just Runs
    }

    private fun HttpRequestBuilder.jsonBody(body: String) {
        session()
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `members read a service with its url and current deployment`() = testApplication {
        val current = service.copy(url = "https://api-shop-acme.liftgate.app", current = Service.Current(UUID.randomUUID(), DeploymentStatus.RUNNING, 1, "a".repeat(40), Instant.now()))
        coEvery { services.withStatus(listOf(service)) } returns listOf(current)
        application { liftgate(app) }
        val response = client.get("/api/v1/services/${service.id}") { session() }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(json.encodeToString(Service.serializer(), current), response.bodyAsText())
    }

    @Test
    fun `non-members are forbidden`() = testApplication {
        coEvery { access.require(org.id, any(), any()) } answers { forbidden() }
        application { liftgate(app) }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/services/${service.id}") { session() }.status)
    }

    @Test
    fun `unknown ids are 404 and malformed ids are 400`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/services/${UUID.randomUUID()}") { session() }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/services/not-a-uuid") { session() }.status)
    }

    @Test
    fun `patch merges onto the stored spec`() = testApplication {
        val spec = slot<ServiceSpec>()
        coEvery { services.update(service.id, capture(spec)) } answers { service.copy(replicas = 3, port = 8080) }
        application { liftgate(app) }
        val response = client.patch("/api/v1/services/${service.id}") { jsonBody("""{"replicas":3,"port":8080,"healthCheckPath":"/healthz"}""") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(service.spec().copy(replicas = 3, port = 8080, healthCheckPath = "/healthz"), spec.captured)
    }

    @Test
    fun `patch rejects invalid specs, naming the field in words a user reads`() = testApplication {
        application { liftgate(app) }
        mapOf(
            """{"replicas":-1}""" to "replicas", """{"replicas":5000}""" to "replicas", """{"cpuMillis":64000}""" to "cpuMillis", """{"memoryMb":1048576}""" to "memoryMb",
            """{"kind":"cron"}""" to "cronSchedule", """{"port":70000}""" to "port", """{"name":" "}""" to "name",
            """{"slug":"Bad Slug"}""" to "slug", """{"slug":"renamed"}""" to null, """{"rootDir":"../.docker"}""" to "rootDir", """{"rootDir":"a/../../b"}""" to "rootDir", """{"rootDir":"$(id)"}""" to "rootDir",
            """{"dockerfilePath":"../src/Dockerfile"}""" to "dockerfilePath", """{"dockerfilePath":"/etc/passwd"}""" to "dockerfilePath", """{"dockerfilePath":""}""" to "dockerfilePath",
            """{"port":8080,"healthCheckPath":"healthz"}""" to "healthCheckPath", """{"port":8080,"healthCheckPath":""}""" to "healthCheckPath",
            """{"port":8080,"healthCheckPath":"/${"a".repeat(256)}"}""" to "healthCheckPath", """{"healthCheckPath":"/healthz"}""" to "healthCheckPath",
            """{"watchPaths":[" "]}""" to "watchPaths", """{"watchPaths":["${"a".repeat(101)}"]}""" to "watchPaths", """{"watchPaths":${(0..20).map { "\"p$it/**\"" }}}""" to "watchPaths",
        ).forEach { (body, field) ->
            val response = client.patch("/api/v1/services/${service.id}") { jsonBody(body) }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, body)
            val error = json.decodeFromString(ErrorBody.serializer(), response.bodyAsText())
            assertEquals(field, error.field, body)
            assertFalse(Regex("[a-z][A-Z]").containsMatchIn(error.message), error.message)
        }
    }

    @Test
    fun `every upper bound the dashboard form offers is accepted`() = testApplication {
        coEvery { services.update(service.id, any()) } answers { secondArg<ServiceSpec>().service(service.id, environment.id) }
        application { liftgate(app) }
        val paths = (1..20).joinToString(",") { "\"${"a".repeat(100)}\"" }
        val body = """{"replicas":10,"cpuMillis":4000,"memoryMb":8192,"port":65535,"healthCheckPath":"/${"a".repeat(255)}","watchPaths":[$paths],"rootDir":"apps/web","dockerfilePath":"docker/Dockerfile.prod"}"""
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/services/${service.id}") { jsonBody(body) }.status)
    }

    @Test
    fun `creating with deploy builds the head of the environment branch and returns the build id`() = testApplication {
        val web = service.copy(kind = ServiceKind.WEB)
        val build = testBuild.copy(serviceId = web.id, commitSha = "c".repeat(40), status = BuildStatus.QUEUED)
        coEvery { services.create(environment.id, any()) } returns web
        coEvery { services.scope(service.id) } returns ServiceScope(web, environment, project, org)
        coEvery { domains.ensurePlatform(any()) } returns mockk()
        every { app.github } returns mockk<GitHubApp> { coEvery { branchHead(42, "acme/shop", "master") } returns ("c".repeat(40) to "first commit") }
        every { app.builds } returns mockk<Builds> { coEvery { request(web.id, "c".repeat(40), "first commit", "master") } returns build }
        application { liftgate(app) }
        val response = client.post("/api/v1/environments/${environment.id}/services?deploy=true") { jsonBody("""{"slug":"api","name":"API","kind":"web"}""") }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(build.id.toString(), body.getValue("buildId").jsonPrimitive.content)
        assertEquals(web, json.decodeFromJsonElement(Service.serializer(), body))
    }

    @Test
    fun `a deploy that cannot resolve the branch removes the new service`() = testApplication {
        coEvery { services.create(environment.id, any()) } returns service
        coEvery { services.delete(service.id) } just Runs
        every { app.github } returns null
        application { liftgate(app) }
        val response = client.post("/api/v1/environments/${environment.id}/services?deploy=true") { jsonBody("""{"slug":"api","name":"API","kind":"worker"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        coVerify { services.delete(service.id) }
    }

    @Test
    fun `creating a service claims a platform hostname only for kinds that serve http`() = testApplication {
        var created = service
        val claimed = mutableListOf<ServiceKind>()
        coEvery { services.create(environment.id, any()) } answers { service.copy(kind = secondArg<ServiceSpec>().kind).also { created = it } }
        coEvery { services.scope(service.id) } answers { ServiceScope(created, environment, project, org) }
        coEvery { domains.ensurePlatform(any()) } answers { claimed += firstArg<ServiceScope>().service.kind; mockk() }
        application { liftgate(app) }
        ServiceKind.entries.forEach {
            val body = """{"slug":"api","name":"API","kind":"${it.sql}","cronSchedule":"0 * * * *"}"""
            assertEquals(HttpStatusCode.Created, client.post("/api/v1/environments/${environment.id}/services") { jsonBody(body) }.status, it.name)
        }
        assertEquals(listOf(ServiceKind.WEB, ServiceKind.STATIC), claimed)
    }

    @Test
    fun `a failed hostname claim removes the new service`() = testApplication {
        val web = service.copy(kind = ServiceKind.WEB)
        coEvery { services.create(environment.id, any()) } returns web
        coEvery { services.scope(service.id) } returns ServiceScope(web, environment, project, org)
        coEvery { domains.ensurePlatform(any()) } throws IllegalStateException("database unavailable")
        coEvery { services.delete(service.id) } just Runs
        application { liftgate(app) }
        val response = client.post("/api/v1/environments/${environment.id}/services") { jsonBody("""{"slug":"api","name":"API","kind":"web"}""") }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        coVerify { services.delete(service.id) }
    }

    @Test
    fun `env var names must be identifiers and unique`() = testApplication {
        application { liftgate(app) }
        listOf("""[{"name":"1BAD","value":"x"}]""", """[{"name":"A","value":"x"},{"name":"A","value":"y"}]""").forEach {
            assertEquals(HttpStatusCode.UnprocessableEntity, client.put("/api/v1/services/${service.id}/env") { jsonBody(it) }.status, it)
        }
    }
}
