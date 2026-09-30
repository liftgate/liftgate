package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.Access
import dev.liftgate.auth.Passkeys
import dev.liftgate.auth.Sessions
import dev.liftgate.cache.Cache
import dev.liftgate.cache.PASSKEY_CHALLENGES_CAP
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
import dev.liftgate.discardingDb
import dev.liftgate.testConfig
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.AfterAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/27/2026
 */
class RateLimitTest {
    companion object {
        private val cache = Cache(testConfig(mapOf("LIFTGATE_HAZELCAST_CLUSTER" to "rate-limit-${UUID.randomUUID()}")))

        @AfterAll
        @JvmStatic
        fun close() = cache.close()
    }

    private val user = User(UUID.randomUUID(), "dean", null, null, null)

    private fun app(vararg env: Pair<String, String>) = mockk<App> {
        every { config } returns testConfig(mapOf(*env))
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { cache } returns RateLimitTest.cache
        every { db } returns discardingDb
        every { passkeys } returns Passkeys(testConfig(), mockk(), RateLimitTest.cache, mockk())
        every { sessions } returns mockk<Sessions> { coEvery { resolve("s") } returns user }
        every { services } returns mockk<Services> { coEvery { scope(testService.id, any()) } returns ServiceScope(testService, testEnvironment, testProject, testOrg) }
        every { access } returns mockk<Access>(relaxUnitFun = true)
        every { builds } returns mockk<Builds> { coEvery { request(any(), any(), any(), any()) } returns testBuild }
        every { podLogs } returns mockk<PodLogs> { every { follow(any(), any(), any()) } returns flow { emit("ready"); awaitCancellation() } }
    }

    private fun freshWindow() = (60_000 - System.currentTimeMillis() % 60_000).let { if (it < 20_000) Thread.sleep(it) }

    private suspend fun ApplicationTestBuilder.options(vararg headers: Pair<String, String>): HttpResponse =
        client.post("/api/v1/auth/passkey/options") { headers.forEach { (name, value) -> header(name, value) } }

    private fun ip() = UUID.randomUUID().toString()

    @Test
    fun `anonymous passkey options are limited per ip and the challenge map stays capped`() {
        freshWindow()
        testApplication {
            application { liftgate(app("LIFTGATE_TRUSTED_PROXIES" to "1")) }
            val ip = ip()
            val responses = List(1000) { options(HttpHeaders.XForwardedFor to ip) }
            assertEquals(List(AUTH_PER_MINUTE) { HttpStatusCode.OK } + List(1000 - AUTH_PER_MINUTE) { HttpStatusCode.TooManyRequests }, responses.map { it.status })
            assertEquals("rate_limited", json.decodeFromString(ErrorBody.serializer(), responses.last().bodyAsText()).error)
            assertTrue(responses.last().headers[HttpHeaders.RetryAfter]!!.toInt() in 1..60)
            assertTrue(cache.passkeyChallenges.size <= PASSKEY_CHALLENGES_CAP)
        }
    }

    @Test
    fun `the passkey challenge map evicts beyond its cap`() {
        repeat(PASSKEY_CHALLENGES_CAP + 5_000) { cache.passkeyChallenges.set("flood-$it", "challenge") }
        assertTrue(cache.passkeyChallenges.size <= PASSKEY_CHALLENGES_CAP, "${cache.passkeyChallenges.size} challenges")
    }

    @Test
    fun `each client ip from a trusted proxy header gets its own bucket`() {
        freshWindow()
        testApplication {
            application { liftgate(app("LIFTGATE_CLIENT_IP_HEADER" to "CF-Connecting-IP", "LIFTGATE_TRUSTED_PROXY_CIDRS" to "127.0.0.0/8,::1/128", "LIFTGATE_TRUSTED_PROXIES" to "1")) }
            val (first, second) = List(2) { ip() }
            repeat(AUTH_PER_MINUTE) { assertEquals(HttpStatusCode.OK, options("CF-Connecting-IP" to first, HttpHeaders.XForwardedFor to ip()).status) }
            assertEquals(HttpStatusCode.TooManyRequests, options("CF-Connecting-IP" to first, HttpHeaders.XForwardedFor to ip()).status)
            assertEquals(HttpStatusCode.OK, options("CF-Connecting-IP" to second).status)
        }
    }

    @Test
    fun `the client ip header is ignored from an untrusted peer`() {
        freshWindow()
        testApplication {
            application { liftgate(app("LIFTGATE_CLIENT_IP_HEADER" to "CF-Connecting-IP", "LIFTGATE_TRUSTED_PROXY_CIDRS" to "10.0.0.0/8", "LIFTGATE_TRUSTED_PROXIES" to "1")) }
            val ip = ip()
            repeat(AUTH_PER_MINUTE) { assertEquals(HttpStatusCode.OK, options("CF-Connecting-IP" to ip(), HttpHeaders.XForwardedFor to ip).status) }
            assertEquals(HttpStatusCode.TooManyRequests, options("CF-Connecting-IP" to ip(), HttpHeaders.XForwardedFor to ip).status)
        }
    }

    @Test
    fun `a forged forwarded-for prefix does not change the key behind two proxies`() {
        freshWindow()
        testApplication {
            application { liftgate(app("LIFTGATE_TRUSTED_PROXIES" to "2")) }
            val client = ip()
            suspend fun hop() = options(HttpHeaders.XForwardedFor to "${ip()}, $client, 10.0.0.1").status
            repeat(AUTH_PER_MINUTE) { assertEquals(HttpStatusCode.OK, hop()) }
            assertEquals(HttpStatusCode.TooManyRequests, hop())
        }
    }

    @Test
    fun `ipv6 clients share one bucket per 64 prefix while ipv4-mapped clients keep their own`() {
        freshWindow()
        testApplication {
            application { liftgate(app("LIFTGATE_TRUSTED_PROXIES" to "1")) }
            suspend fun from(ip: String) = options(HttpHeaders.XForwardedFor to ip).status
            val exhausted = List(AUTH_PER_MINUTE) { HttpStatusCode.OK } + HttpStatusCode.TooManyRequests
            assertEquals(exhausted, List(AUTH_PER_MINUTE + 1) { from("2001:db8::${it + 1}") })
            assertEquals(HttpStatusCode.OK, from("2001:db8:0:1::1"))
            assertEquals(exhausted, List(AUTH_PER_MINUTE + 1) { from("::ffff:192.0.2.1") })
            assertEquals(HttpStatusCode.OK, from("::ffff:192.0.2.2"))
        }
    }

    @Test
    fun `deploys are limited per service and only authorised deploys count`() {
        freshWindow()
        testApplication {
            application { liftgate(app()) }
            val id = testService.id.toString()
            suspend fun deploy(path: String, signedIn: Boolean = true) = client.post("/api/v1/services/$path/deploy") {
                if (signedIn) session()
                contentType(ContentType.Application.Json)
                setBody("""{"ref":"dddddddddddddddddddddddddddddddddddddddd"}""")
            }.status
            assertEquals(List(20) { HttpStatusCode.Unauthorized }, List(20) { deploy(id, signedIn = false) })
            val statuses = List(20) { deploy(if (it % 2 == 0) id.uppercase() else id) }
            assertEquals(List(DEPLOYS_PER_MINUTE) { HttpStatusCode.Created } + List(20 - DEPLOYS_PER_MINUTE) { HttpStatusCode.TooManyRequests }, statuses)
        }
    }

    @Test
    fun `a user's log sockets past the limit are refused until one closes`() = testApplication {
        application { liftgate(app()) }
        val sockets = createClient { install(WebSockets) }
        suspend fun open() = sockets.webSocketSession("/api/v1/logs/services/${testService.id}") { session() }
        val streams = List(LOG_SOCKETS_PER_USER) { open().also { assertEquals("ready", (it.incoming.receive() as Frame.Text).readText()) } }
        assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, open().closeReason.await()?.code)
        streams.first().close()
        assertNotNull((1..50).firstNotNullOfOrNull { delay(20); open().takeIf { it.incoming.receiveCatching().getOrNull() is Frame.Text } })
    }

    @Test
    fun `a log socket frame over the cap closes the socket as too big`() = testApplication {
        application { liftgate(app()) }
        val socket = createClient { install(WebSockets) }.webSocketSession("/api/v1/logs/services/${testService.id}") { session() }
        socket.send(Frame.Binary(true, ByteArray(WEBSOCKET_FRAME_LIMIT.toInt() + 1)))
        assertEquals(CloseReason.Codes.TOO_BIG.code, socket.closeReason.await()?.code)
    }
}
