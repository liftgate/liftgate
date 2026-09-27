package dev.liftgate.events

import dev.liftgate.TestNats
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * @author Dean
 * @date 9/27/2026
 */
class NatsTest {
    private val payload = buildJsonObject { put("buildId", "b1") }

    @Test
    fun `a handler that fails seven times is retried with growing delays and handled once`() = runBlocking {
        val nats = TestNats.clean()
        TestNats.streams.addOrUpdateConsumer(
            "LIFTGATE",
            ConsumerConfiguration.builder().durable("retry-test").filterSubject(Subject.BUILD_REQUESTED.value).ackPolicy(AckPolicy.Explicit).maxDeliver(5).build(),
        )
        val attempts = AtomicInteger()
        val handled = CompletableDeferred<Unit>()
        val consumer = nats.consume(Subject.BUILD_REQUESTED, "retry-test", this, Duration.ofSeconds(1)) {
            if (attempts.incrementAndGet() <= 7) error("attempt ${attempts.get()} fails")
            handled.complete(Unit)
        }
        nats.publish(Subject.BUILD_REQUESTED.value, 1, payload)
        val elapsed = measureTime { withTimeout(30.seconds) { handled.await() } }
        delay(3.seconds)
        assertEquals(8, attempts.get())
        assertTrue(elapsed >= 1.seconds, "seven retries took only $elapsed")
        consumer.cancel()
    }

    @Test
    fun `cancelling a consumer mid-handler hands the message to another consumer`() = runBlocking {
        val nats = TestNats.clean()
        val first = CoroutineScope(Job())
        val started = CompletableDeferred<Unit>()
        nats.consume(Subject.RELEASE_REQUESTED, "handover-test", first) {
            started.complete(Unit)
            awaitCancellation()
        }
        nats.publish(Subject.RELEASE_REQUESTED.value, 1, payload)
        withTimeout(10.seconds) { started.await() }
        val received = CompletableDeferred<JsonObject>()
        val second = nats.consume(Subject.RELEASE_REQUESTED, "handover-test", this) { received.complete(it) }
        first.cancel()
        assertEquals(payload, withTimeout(5.seconds) { received.await() })
        second.cancel()
    }

    @Test
    fun `a deleted consumer is recreated and its poll time advances`() = runBlocking {
        val nats = TestNats.clean()
        val received = CompletableDeferred<JsonObject>()
        val consumer = nats.consume(Subject.TEARDOWN_REQUESTED, "recreate-test", this) { received.complete(it) }
        val registered = nats.lastPolls.getValue("recreate-test")
        withTimeout(10.seconds) { while ("recreate-test" !in TestNats.streams.getConsumerNames("LIFTGATE")) delay(100) }
        TestNats.streams.deleteConsumer("LIFTGATE", "recreate-test")
        nats.publish(Subject.TEARDOWN_REQUESTED.value, 1, payload)
        assertEquals(payload, withTimeout(20.seconds) { received.await() })
        assertTrue(nats.lastPolls.getValue("recreate-test") > registered)
        consumer.cancel()
    }

    @Test
    fun `a handler asking for redelivery gets the message back after exactly that delay`() = runBlocking {
        val nats = TestNats.clean()
        val deliveries = CopyOnWriteArrayList<Long>()
        val handled = CompletableDeferred<Unit>()
        val consumer = nats.consume(Subject.BUILD_REQUESTED, "redeliver-test", this) {
            deliveries += System.nanoTime()
            if (deliveries.size < 3) throw Redeliver(Duration.ofSeconds(1))
            handled.complete(Unit)
        }
        nats.publish(Subject.BUILD_REQUESTED.value, 1, payload)
        withTimeout(20.seconds) { handled.await() }
        val gaps = deliveries.zipWithNext { first, next -> (next - first).nanoseconds }
        assertTrue(gaps.size == 2 && gaps.all { it >= 900.milliseconds }, "redelivered after $gaps")
        consumer.cancel()
    }

    @Test
    fun `an unparseable message is terminated instead of retried`() = runBlocking {
        val nats = TestNats.clean()
        val received = CompletableDeferred<JsonObject>()
        val consumer = nats.consume(Subject.BUILD_REQUESTED, "term-test", this) { received.complete(it) }
        nats.publishLog(Subject.BUILD_REQUESTED.value, "not json")
        nats.publish(Subject.BUILD_REQUESTED.value, 1, payload)
        assertEquals(payload, withTimeout(10.seconds) { received.await() })
        delay(1.seconds)
        assertEquals(0L, TestNats.streams.getConsumerInfo("LIFTGATE", "term-test").numAckPending)
        consumer.cancel()
    }

    @Test
    fun `ensureStream keeps subjects added by a newer release and keeps messages for seven days`() {
        val nats = TestNats.clean()
        TestNats.addSubject("liftgate.newer.subject")
        nats.ensureStream()
        val stream = TestNats.streams.getStreamInfo("LIFTGATE").configuration
        assertEquals(Subject.entries.map { it.value }.toSet() + "liftgate.newer.subject", stream.subjects.toSet())
        assertEquals(Duration.ofDays(7), stream.maxAge)
        assertEquals(Duration.ofMinutes(2), stream.duplicateWindow)
    }
}
