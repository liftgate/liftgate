package dev.liftgate.events

import dev.liftgate.TestDatabase
import dev.liftgate.TestNats
import dev.liftgate.db.Housekeeping
import dev.liftgate.db.Outbox
import dev.liftgate.db.now
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
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

    @Test
    fun `relay publishes a row whose subject this release does not know and the rows after it`() = runBlocking {
        val db = TestDatabase.clean()
        val nats = TestNats.clean()
        TestNats.addSubject("liftgate.newer.subject")
        db.tx {
            Outbox.insert {
                it[subject] = "liftgate.newer.subject"
                it[payload] = buildJsonObject {}
            }
            enqueue(Subject.BUILD_REQUESTED, buildJsonObject { put("buildId", "b0") })
        }

        assertEquals(2, OutboxRelay(db, nats).runOnce())
        assertEquals(2L, TestNats.streams.getStreamInfo("LIFTGATE").streamState.msgCount)
        assertEquals(0L, db.tx { Outbox.selectAll().where { Outbox.publishedAt.isNull() }.count() })
    }

    @Test
    fun `housekeeping deletes rows published more than seven days ago`() = runBlocking {
        val db = TestDatabase.clean()
        db.tx {
            listOf(now().minusDays(8), now().minusDays(6), null).forEach { at ->
                Outbox.insert {
                    it[subject] = Subject.BUILD_REQUESTED.value
                    it[payload] = buildJsonObject {}
                    it[publishedAt] = at
                }
            }
        }

        Housekeeping(db).runOnce()
        assertEquals(listOf(2L, 3L), db.tx { Outbox.selectAll().orderBy(Outbox.id).map { it[Outbox.id] } })
    }
}
