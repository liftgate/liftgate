package dev.liftgate.events

import dev.liftgate.TestDatabase
import dev.liftgate.TestNats
import dev.liftgate.db.Outbox
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * @author Dean
 * @date 9/17/2026
 */
class OutboxTest {
    @Test
    fun `relay publishes pending rows in order and marks them published`() = runBlocking {
        val db = TestDatabase.clean()
        val nats = TestNats.clean()
        val builds = List(3) { buildJsonObject { put("buildId", "b$it") } }
        db.tx { builds.forEach { enqueue(Subject.BUILD_REQUESTED, it) } }
        val relay = OutboxRelay(db, nats)

        assertEquals(3, relay.runOnce())
        assertEquals(0, relay.runOnce())
        assertEquals(0L, db.tx { Outbox.selectAll().where { Outbox.publishedAt.isNull() }.count() })
        val received = Channel<JsonObject>(Channel.UNLIMITED)
        val consumer = nats.consume(Subject.BUILD_REQUESTED, "outbox-test", this) { received.send(it) }
        assertEquals(builds, List(builds.size) { withTimeout(10.seconds) { received.receive() } })
        consumer.cancel()
    }
}
