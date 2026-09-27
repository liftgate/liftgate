package dev.liftgate.events

import dev.liftgate.TestNats
import io.nats.client.api.StorageType
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(listOf("cloning", "building", "pushing", "Build succeeded"), withTimeout(10.seconds) { logs.follow(build, finished = true).toList() })
    }

    @Test
    fun `a viewer who joins mid-build gets the history and then live lines until the end marker`() = runBlocking {
        publish("cloning")
        val lines = async { logs.follow(build, finished = false).toList() }
        delay(1.seconds)
        publish("building")
        logs.end(build, "the build job failed").get()
        assertEquals(listOf("cloning", "building", "Build failed: the build job failed"), withTimeout(10.seconds) { lines.await() })
    }

    @Test
    fun `a finished build without an end marker stops after its last line and at once when nothing was kept`() = runBlocking {
        publish("cloning", "building")
        assertEquals(listOf("cloning", "building"), withTimeout(10.seconds) { logs.follow(build, finished = true).toList() })
        assertEquals(emptyList<String>(), withTimeout(10.seconds) { logs.follow(UUID.randomUUID(), finished = true).toList() })
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
