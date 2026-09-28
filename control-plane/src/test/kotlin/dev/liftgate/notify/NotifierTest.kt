package dev.liftgate.notify

import dev.liftgate.App
import dev.liftgate.TestNats
import dev.liftgate.build.Webhooks
import dev.liftgate.events.Redeliver
import dev.liftgate.events.Subject
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.json
import dev.liftgate.notify.NotificationChannel.Kind
import dev.liftgate.notify.NotificationChannels.Endpoint
import dev.liftgate.testConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * @author Dean
 * @date 9/27/2026
 */
class NotifierTest {
    private val requests = CopyOnWriteArrayList<Pair<Instant, HttpRequestData>>()
    private var answers = mutableListOf<HttpStatusCode>()
    private val engine = MockEngine { request ->
        requests += Instant.now() to request
        respond("", answers.removeFirstOrNull() ?: HttpStatusCode.NoContent)
    }
    private val orgId = UUID.randomUUID()
    private val channelId = UUID.randomUUID()
    private val channels = mockk<NotificationChannels>()
    private val app = mockk<App> {
        every { config } returns testConfig(mapOf("LIFTGATE_NOTIFICATION_DENIED_CIDRS" to "203.0.113.7/32"))
        every { notificationChannels } returns channels
    }
    private val buildId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val text = "Build of shop/api in production at abc1234 failed: the build job failed"
    private val url = "https://liftgate.example/acme/shop/production/api?tab=builds&build=$buildId"
    private val notification = Notification("build_failed", text, url, "acme", "shop", "production", "api", "abc1234", buildId, null, Instant.parse("2026-09-27T12:00:00Z"))
    private val hook = "https://hooks.example.com/services/T000/B000/xyz"

    private fun body(index: Int = 0) = (requests[index].second.body as TextContent).text

    private fun payload(age: Duration) = buildJsonObject {
        put("orgId", orgId.toString())
        put("channelId", channelId.toString())
        put("notification", json.encodeToJsonElement(notification.copy(at = Instant.now().minus(age))))
    }

    @Test
    fun `slack gets text and discord gets content, both unsigned`() = runBlocking {
        val notifier = Notifier(app, engine = engine)
        assertNull(notifier.send(Endpoint(Kind.SLACK, hook, null), notification))
        assertNull(notifier.send(Endpoint(Kind.DISCORD, "https://discord.com/api/webhooks/1/token", null), notification))
        assertEquals("""{"text":"$text\n$url"}""", body(0))
        assertEquals("""{"content":"$text\n$url"}""", body(1))
        assertTrue(requests.all { it.second.headers["X-Liftgate-Signature"] == null })
    }

    @Test
    fun `a webhook gets the notification as json signed with the channel secret`() = runBlocking {
        assertNull(Notifier(app, engine = engine).send(Endpoint(Kind.WEBHOOK, "https://ops.example.com/liftgate", "whsec"), notification))
        val expected = """{"event":"build_failed","text":"$text","url":"$url","org":"acme","project":"shop","environment":"production","service":"api",""" +
            """"commitSha":"abc1234","buildId":"$buildId","deploymentId":null,"at":"2026-09-27T12:00:00Z"}"""
        assertEquals(expected, body())
        val signature = requests.single().second.headers["X-Liftgate-Signature"]
        assertTrue(Webhooks.verify("whsec", body().toByteArray(), signature))
        assertFalse(Webhooks.verify("other", body().toByteArray(), signature))
    }

    @Test
    fun `private, loopback, link-local, multicast, IPv6 and configured addresses are refused when a channel is saved`() = runBlocking {
        var address = "10.0.0.1"
        val notifier = Notifier(app, lookup = { listOf(InetAddress.getByName(address)) })
        listOf(
            "10.0.0.1", "172.16.0.1", "192.168.1.1", "100.64.0.1", "169.254.169.254", "127.0.0.1", "0.0.0.0", "224.0.0.1",
            "255.255.255.255", "::ffff:10.0.0.1", "::1", "fd00::1", "2606:4700:4700::1111", "203.0.113.7",
        ).forEach {
            address = it
            assertEquals(HttpStatusCode.UnprocessableEntity, assertFailsWith<LiftgateException>(it) { notifier.check(hook) }.status, it)
        }
        address = "93.184.216.34"
        assertEquals(hook, notifier.check(hook))
        assertEquals(HttpStatusCode.UnprocessableEntity, assertFailsWith<LiftgateException> { notifier.check("http://hooks.example.com/x") }.status)
    }

    @Test
    fun `a hostname that resolved publicly when saved is refused when it resolves to a private address at send time`() = runBlocking {
        var address = "93.184.216.34"
        val lookups = CopyOnWriteArrayList<String>()
        val notifier = Notifier(app, lookup = { lookups += it; listOf(InetAddress.getByName(address)) })
        assertEquals(hook, notifier.check(hook))
        address = "10.0.0.1"
        val failure = assertNotNull(notifier.send(Endpoint(Kind.SLACK, hook, null), notification))
        assertTrue("hooks.example.com has no public IPv4 address" in failure, failure)
        assertEquals(listOf("hooks.example.com", "hooks.example.com"), lookups)
    }

    @Test
    fun `an endpoint answering 500 is redelivered after a delay that grows with the notification's age until it is an hour old`() = runBlocking {
        coEvery { channels.endpoint(orgId, channelId) } returns Endpoint(Kind.DISCORD, "https://discord.com/api/webhooks/1/token", null)
        answers = MutableList(3) { HttpStatusCode.InternalServerError }
        val notifier = Notifier(app, engine = engine)
        assertEquals(Duration.ofSeconds(10), assertFailsWith<Redeliver> { notifier.deliver(payload(Duration.ZERO)) }.delay)
        val later = assertFailsWith<Redeliver> { notifier.deliver(payload(Duration.ofMinutes(2))) }.delay
        assertTrue(later >= Duration.ofMinutes(2) && later < Duration.ofMinutes(3), "$later")
        assertEquals(Duration.ofMinutes(10), assertFailsWith<Redeliver> { notifier.deliver(payload(Duration.ofMinutes(30))) }.delay)
        notifier.deliver(payload(Duration.ofMinutes(61)))
        assertEquals(3, requests.size)
        notifier.deliver(payload(Duration.ofMinutes(5)))
        assertEquals(4, requests.size)
    }

    @Test
    fun `a failed delivery comes back through the stream and is acked once the endpoint answers 2xx`() = runBlocking {
        coEvery { channels.endpoint(orgId, channelId) } returns Endpoint(Kind.SLACK, hook, null)
        answers = mutableListOf(HttpStatusCode.InternalServerError)
        val nats = TestNats.clean()
        val notifier = Notifier(app, engine = engine)
        val delivered = CompletableDeferred<Unit>()
        val consumer = nats.consume(Subject.NOTIFICATION_REQUESTED, "notification-test", this) {
            notifier.deliver(it)
            delivered.complete(Unit)
        }
        nats.publish(Subject.NOTIFICATION_REQUESTED.value, 1, payload(Duration.ZERO))
        withTimeout(30.seconds) { delivered.await() }
        val (first, second) = requests.map { it.first }
        assertEquals(2, requests.size)
        assertTrue(Duration.between(first, second) >= Duration.ofSeconds(9), "redelivered after ${Duration.between(first, second)}")
        consumer.cancel()
    }
}
