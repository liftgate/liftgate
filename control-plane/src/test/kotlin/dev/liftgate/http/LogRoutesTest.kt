package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.TestNats
import dev.liftgate.auth.Access
import dev.liftgate.auth.Sessions
import dev.liftgate.deploy.Builds
import dev.liftgate.k8s.PodLogs
import dev.liftgate.k8s.testBuild
import dev.liftgate.k8s.testEnvironment
import dev.liftgate.k8s.testOrg
import dev.liftgate.k8s.testProject
import dev.liftgate.k8s.testService
import dev.liftgate.org.User
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * @author Dean
 * @date 9/27/2026
 */
@EnableKubernetesMockClient
class LogRoutesTest {
    lateinit var server: KubernetesMockServer
    lateinit var client: KubernetesClient

    private val user = User(UUID.randomUUID(), "dean", null, null, null)
    private val events = TestNats.clean()
    private val guard = mockk<Access>(relaxUnitFun = true)
    private val pods = "/api/v1/namespaces/${testEnvironment.namespace}/pods"

    private fun app() = mockk<App> {
        every { config } returns testConfig()
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { cache } returns unlimitedCache
        every { sessions } returns mockk<Sessions> { coEvery { resolve("s") } returns user }
        every { builds } returns mockk<Builds> { coEvery { byId(testBuild.id) } returns testBuild }
        every { services } returns mockk<Services> { coEvery { scope(testService.id) } returns ServiceScope(testService, testEnvironment, testProject, testOrg) }
        every { access } returns guard
        every { nats } returns events
        every { podLogs } returns PodLogs(client, 100.milliseconds)
    }

    private fun pod(name: String, restarts: Int = 0): Pod = PodBuilder()
        .withNewMetadata().withName(name).withNamespace(testEnvironment.namespace).endMetadata()
        .withNewStatus().addNewContainerStatus().withName("app").withRestartCount(restarts).withNewState().withNewRunning().endRunning().endState().endContainerStatus().endStatus()
        .build()

    private fun listing(vararg items: Pod) = server.expect().get().withPath("$pods?labelSelector=liftgate.dev%2Fservice-id%3D${testService.id}")
        .andReturn(200, PodListBuilder().withItems(*items).build())

    private fun log(pod: String, query: String, body: String) = server.expect().get().withPath("$pods/$pod/log?pretty=false$query").andReturn(200, body).once()

    private suspend fun ApplicationTestBuilder.open(path: String) = createClient { install(WebSockets) }.webSocketSession("/api/v1/logs/$path") { session() }

    private suspend fun DefaultClientWebSocketSession.lines() = withTimeout(10.seconds) { incoming.consumeAsFlow().map { (it as Frame.Text).readText() }.toList() }

    @Test
    fun `a finished build replays from its first line and the socket closes after the end marker`() = testApplication {
        application { liftgate(app()) }
        listOf("cloning", "building").forEach { events.logs.publish(testBuild.id, it).get() }
        events.logs.end(testBuild.id, null).get()
        val socket = open("builds/${testBuild.id}")
        assertEquals(listOf("cloning", "building", "Build succeeded"), socket.lines())
        assertEquals(CloseReason.Codes.NORMAL.code, socket.closeReason.await()?.code)
    }

    @Test
    fun `runtime logs tail each pod with its name and pick up a replacement pod on the next listing`() = testApplication {
        application { liftgate(app()) }
        listing(pod("api-7d9f-aaaaa")).once()
        listing(pod("api-7d9f-aaaaa"), pod("api-7d9f-bbbbb")).always()
        log("api-7d9f-aaaaa", "&tailLines=500&timestamps=true&follow=true", "2026-09-28T10:00:00Z hello-liftgate\n")
        log("api-7d9f-bbbbb", "&tailLines=500&timestamps=true&follow=true", "2026-09-28T10:00:05Z hello again\n")
        val socket = open("services/${testService.id}")
        val lines = withTimeout(10.seconds) { List(2) { (socket.incoming.receive() as Frame.Text).readText() } }
        assertEquals(setOf("aaaaa 2026-09-28T10:00:00Z hello-liftgate", "bbbbb 2026-09-28T10:00:05Z hello again"), lines.toSet())
        socket.close()
    }

    @Test
    fun `previous returns the crashed container's output and ends the stream`() = testApplication {
        application { liftgate(app()) }
        listing(pod("api-7d9f-aaaaa", restarts = 1)).always()
        log("api-7d9f-aaaaa", "&previous=true&tailLines=500&timestamps=true", "2026-09-28T10:00:00Z booting\n2026-09-28T10:00:01Z panic: boom\n")
        val socket = open("services/${testService.id}?previous=true")
        assertEquals(listOf("aaaaa 2026-09-28T10:00:00Z booting", "aaaaa 2026-09-28T10:00:01Z panic: boom"), socket.lines())
        assertEquals(CloseReason.Codes.NORMAL.code, socket.closeReason.await()?.code)
    }

    @Test
    fun `a user outside the org is closed with a policy violation`() = testApplication {
        application { liftgate(app()) }
        coEvery { guard.require(testOrg.id, any(), any()) } throws LiftgateException(HttpStatusCode.Forbidden, "forbidden", "insufficient permissions")
        listOf("services/${testService.id}", "builds/${testBuild.id}").forEach {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, open(it).closeReason.await()?.code)
        }
    }
}
