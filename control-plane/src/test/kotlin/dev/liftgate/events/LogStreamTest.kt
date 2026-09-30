package dev.liftgate.events

import dev.liftgate.TestNats
import io.nats.client.api.StorageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * @author Dean
 * @date 9/27/2026
 */
class LogStreamTest {
    private val logs = TestNats.clean().logs
    private val build = UUID.randomUUID()

    private fun publish(vararg lines: String) = lines.forEach { logs.publish(build, it).get() }

    @Test
    fun `a late subscriber to a finished build receives every line from the first and stops at the end marker`() = runBlocking {
        publish("cloning", "building", "pushing")
        logs.end(build, null).get()
        assertEquals(listOf("cloning", "building", "pushing", "Build succeeded"), withTimeout(10.seconds) { logs.follow(build) { true }.toList() })
    }

    @Test
    fun `a viewer who joins mid-build gets the history and then live lines until the end marker`() = runBlocking {
        publish("cloning")
        val lines = async { logs.follow(build) { false }.toList() }
        delay(1.seconds)
        publish("building")
        logs.end(build, "the build job failed").get()
        assertEquals(listOf("cloning", "building", "Build failed: the build job failed"), withTimeout(10.seconds) { lines.await() })
    }

    @Test
    fun `a finished build without an end marker stops after its last line and at once when nothing was kept`() = runBlocking {
        publish("cloning", "building")
        assertEquals(listOf("cloning", "building"), withTimeout(10.seconds) { logs.follow(build) { true }.toList() })
        assertEquals(emptyList<String>(), withTimeout(10.seconds) { logs.follow(UUID.randomUUID()) { true }.toList() })
    }

    @Test
    fun `a live viewer is released once the build has ended even if its end marker never arrives`() = runBlocking {
        val ended = AtomicBoolean()
        publish("cloning")
        val lines = async { logs.follow(build, Duration.ofMillis(200)) { ended.get() }.toList() }
        delay(1.seconds)
        publish("building")
        ended.set(true)
        assertEquals(listOf("cloning", "building"), withTimeout(10.seconds) { lines.await() })
    }

    @Test
    fun `five hundred live viewers add fewer than ten threads`() = runBlocking {
        publish("cloning")
        val threads = ManagementFactory.getThreadMXBean()
        val before = threads.threadCount
        val viewers = List(500) {
            val first = CompletableDeferred<Unit>()
            launch { logs.follow(build) { false }.collect { first.complete(Unit) } }.also { withTimeout(10.seconds) { first.await() } }
        }
        val added = threads.threadCount - before
        viewers.forEach { it.cancel() }
        assertTrue(added < 10, "500 viewers added $added threads")
    }

    @Test
    fun `a viewer that stops reading holds back delivery and still gets every line in order once it reads again`() = runBlocking {
        val lines = List(20_000) { "line $it" }
        lines.chunked(1_000).forEach { chunk -> chunk.map { logs.publish(build, it) }.forEach { it.get() } }
        logs.end(build, null).get()
        val reading = CompletableDeferred<Unit>()
        val received = async { logs.follow(build) { true }.onEach { reading.await() }.toList() }
        delay(2.seconds)
        val delivered = TestNats.streams.getConsumers("LIFTGATE_LOGS").sumOf { it.delivered.consumerSequence }
        reading.complete(Unit)
        assertTrue(delivered < 2_000, "a stalled viewer was sent $delivered lines")
        assertEquals(lines + "Build succeeded", withTimeout(30.seconds) { received.await() })
    }

    @Test
    fun `build logs are kept on disk for seven days within the per-build and byte caps`() {
        val stream = TestNats.streams.getStreamInfo("LIFTGATE_LOGS").configuration
        assertEquals(listOf("liftgate.logs.build.>"), stream.subjects)
        assertEquals(StorageType.File, stream.storageType)
        assertEquals(Duration.ofDays(7), stream.maxAge)
        assertEquals(50_000L, stream.maxMsgsPerSubject)
        assertEquals(536_870_912L, stream.maxBytes)
    }
}
