package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.Access
import dev.liftgate.auth.Sessions
import dev.liftgate.org.Organization
import dev.liftgate.org.User
import dev.liftgate.project.Environment
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.Project
import dev.liftgate.service.BuildStrategy
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Dean
 * @date 9/17/2026
 */
class ServiceRoutesTest {
    private val user = User(UUID.randomUUID(), "dean", null, null, null)
    private val org = Organization(UUID.randomUUID(), "acme", "Acme", "free")
    private val project = Project(UUID.randomUUID(), org.id, "shop", "Shop", "acme/shop", "main", 42)
    private val environment = Environment(UUID.randomUUID(), project.id, "production", "Production", EnvironmentKind.PRODUCTION, "main", "env-0123456789ab")
    private val service = Service(UUID.randomUUID(), environment.id, "api", "API", ServiceKind.WORKER, "/", BuildStrategy.AUTO, "Dockerfile", null, 1, 500, 512, null, null)
    private val services = mockk<Services>()
    private val access = mockk<Access>()
    private val app = mockk<App>().also {
        every { it.services } returns services
        every { it.access } returns access
        every { it.sessions } returns mockk<Sessions> { coEvery { resolve("s") } returns user }
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.config } returns testConfig()
    }

    init {
        coEvery { services.scope(any()) } returns null
        coEvery { services.scope(service.id) } returns ServiceScope(service, environment, project, org)
        coEvery { access.require(org.id, any(), any()) } just Runs
    }

    private fun HttpRequestBuilder.session() = cookie(SESSION_COOKIE, "s")

    private fun HttpRequestBuilder.jsonBody(body: String) {
        session()
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `members read a service`() = testApplication {
        application { liftgate(app) }
        val response = client.get("/api/v1/services/${service.id}") { session() }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(json.encodeToString(Service.serializer(), service), response.bodyAsText())
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
        val response = client.patch("/api/v1/services/${service.id}") { jsonBody("""{"replicas":3,"port":8080}""") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(service.spec().copy(replicas = 3, port = 8080), spec.captured)
    }

    @Test
    fun `patch rejects invalid specs`() = testApplication {
        application { liftgate(app) }
        listOf(
            """{"replicas":-1}""", """{"replicas":5000}""", """{"cpuMillis":64000}""", """{"memoryMb":1048576}""", """{"kind":"cron"}""", """{"port":70000}""",
            """{"slug":"Bad Slug"}""", """{"slug":"renamed"}""", """{"rootDir":"../.docker"}""", """{"rootDir":"a/../../b"}""", """{"rootDir":"$(id)"}""",
            """{"dockerfilePath":"../src/Dockerfile"}""", """{"dockerfilePath":"/etc/passwd"}""", """{"dockerfilePath":""}""",
        ).forEach {
            assertEquals(HttpStatusCode.UnprocessableEntity, client.patch("/api/v1/services/${service.id}") { jsonBody(it) }.status, it)
        }
    }

    @Test
    fun `env var names must be identifiers and unique`() = testApplication {
        application { liftgate(app) }
        listOf("""[{"name":"1BAD","value":"x"}]""", """[{"name":"A","value":"x"},{"name":"A","value":"y"}]""").forEach {
            assertEquals(HttpStatusCode.UnprocessableEntity, client.put("/api/v1/services/${service.id}/env") { jsonBody(it) }.status, it)
        }
    }
}
