package dev.liftgate.events

import dev.liftgate.db.Db
import dev.liftgate.db.Outbox
import dev.liftgate.db.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

private const val BATCH = 100

/**
 * @author Dean
 * @date 9/17/2026
 */
class OutboxRelay(private val db: Db, private val nats: Nats) {
    private val log = LoggerFactory.getLogger(OutboxRelay::class.java)

    suspend fun runOnce(): Int {
        val pending = db.tx {
            Outbox.select(Outbox.id, Outbox.subject, Outbox.payload)
                .where { Outbox.publishedAt.isNull() }
                .orderBy(Outbox.id)
                .limit(BATCH)
                .map { Triple(it[Outbox.id], Subject.of(it[Outbox.subject]), it[Outbox.payload]) }
        }
        pending.forEach { (id, subject, payload) ->
            nats.publish(subject, id, payload)
            db.tx { Outbox.update({ Outbox.id eq id }) { it[publishedAt] = now() } }
        }
        return pending.size
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            val published = runCatching { runOnce() }.getOrElse { ensureActive(); log.warn("outbox relay failed", it); 0 }
            if (published < BATCH) delay(1000)
        }
    }
}
