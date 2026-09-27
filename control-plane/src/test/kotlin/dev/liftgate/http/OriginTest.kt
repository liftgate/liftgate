package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.Access
import dev.liftgate.auth.ApiTokens
import dev.liftgate.auth.Sessions
import dev.liftgate.deploy.Deployments
import dev.liftgate.events.Nats
import dev.liftgate.k8s.testDeployment
import dev.liftgate.k8s.testEnvironment
import dev.liftgate.k8s.testOrg
import dev.liftgate.k8s.testProject
import dev.liftgate.k8s.testService
import dev.liftgate.org.Orgs
import dev.liftgate.org.User
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

private const val EVIL = "https://evil.example"

/**
 * @author Dean
 * @date 9/27/2026
 */
class OriginTest {
    private val user = User(UUID.randomUUID(), "dean", null, null, null)
    private val sessions = mockk<Sessions> {
        coEvery { resolve("s") } returns user
        coEvery { delete("s") } just Runs
    }
    private val deployments = mockk<Deployments> {
        coEvery { byId(testDeployment.id) } returns testDeployment
        coEvery { rollback(testDeployment.id) } returns testDeployment
    }
    private val app = mockk<App> {
        every { config } returns testConfig()
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { cache } returns unlimitedCache
        every { this@mockk.sessions } returns this@OriginTest.sessions
        every { this@mockk.deployments } returns this@OriginTest.deployments
        every { apiTokens } returns mockk<ApiTokens> { coEvery { resolve("lg_token") } returns (testOrg.id to user.id) }
        every { orgs } returns mockk<Orgs> { coEvery { user(user.id) } returns user }
        every { services } returns mockk<Services> { coEvery { scope(testService.id) } returns ServiceScope(testService, testEnvironment, testProject, testOrg) }
        every { access } returns mockk<Access>(relaxUnitFun = true)
        every { nats } returns mockk<Nats> { every { logs(any()) } returns flowOf("ready") }
    }

    private suspend fun ApplicationTestBuilder.rollback(block: HttpRequestBuilder.() -> Unit) =
        client.post("/api/v1/deployments/${testDeployment.id}/rollback", block)

    @Test
    fun `a cookie write needs the dashboard origin`() = testApplication {
        application { liftgate(app) }
        val foreign = rollback { session(EVIL) }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)
        assertEquals("bad_origin", json.decodeFromString(ErrorBody.serializer(), foreign.bodyAsText()).error)
        assertEquals(HttpStatusCode.Forbidden, rollback { session(null) }.status)
        coVerify(exactly = 0) { deployments.rollback(any()) }
        assertEquals(HttpStatusCode.Created, rollback { session() }.status)
    }

    @Test
    fun `a bearer write needs no origin`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.Created, rollback { header(HttpHeaders.Authorization, "Bearer lg_token") }.status)
    }

    @Test
    fun `logout needs the dashboard origin and clears the secure host-only cookie`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/auth/logout") { session(EVIL) }.status)
        coVerify(exactly = 0) { sessions.delete(any()) }
        val response = client.post("/api/v1/auth/logout") { session() }
        assertEquals(HttpStatusCode.NoContent, response.status)
        val cleared = response.headers[HttpHeaders.SetCookie].orEmpty()
        assertTrue(cleared.startsWith("__Host-liftgate_session=;") && "Secure" in cleared && "Path=/" in cleared, cleared)
    }

    @Test
    fun `a foreign origin cannot open a log socket`() = testApplication {
        application { liftgate(app) }
        val sockets = createClient { install(WebSockets) }
        val path = "/api/v1/logs/services/${testService.id}"
        assertFails { sockets.webSocket(path, { session(EVIL) }) { incoming.receive() } }
        sockets.webSocket(path, { session() }) { assertEquals("ready", (incoming.receive() as Frame.Text).readText()) }
    }
}
