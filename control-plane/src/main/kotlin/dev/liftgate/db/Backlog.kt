package dev.liftgate.db

import dev.liftgate.deploy.BuildStatus
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val interval = 15.seconds
private val waiting = listOf(BuildStatus.QUEUED, BuildStatus.RUNNING).map { it.sql }

/**
 * @author Dean
 * @date 9/27/2026
 */
class Backlog(private val db: Db, private val metrics: MeterRegistry) {
    private val log = LoggerFactory.getLogger(Backlog::class.java)

    @Volatile
    private var pending = 0L

    @Volatile
    private var oldest: Instant? = null

    @Volatile
    private var builds = emptyMap<String, Long>()

    suspend fun sample() {
        val rows = Outbox.id.count()
        val first = Outbox.createdAt.min()
        val count = Builds.id.count()
        db.tx {
            val outbox = Outbox.select(rows, first).where { Outbox.publishedAt.isNull() }.single()
            pending = outbox[rows]
            oldest = outbox[first]?.toInstant()
            builds = Builds.select(Builds.status, count).where { Builds.status inList waiting }.groupBy(Builds.status).associate { it[Builds.status] to it[count] }
        }
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        val gauges = listOf(
            Gauge.builder("liftgate.outbox.pending") { pending }.register(metrics),
            Gauge.builder("liftgate.outbox.oldest.pending") { oldest?.let { Duration.between(it, Instant.now()).toMillis() / 1000.0 } ?: 0.0 }
                .baseUnit("seconds")
                .register(metrics),
        ) + waiting.map { status -> Gauge.builder("liftgate.builds") { builds[status] ?: 0L }.tag("status", status).register(metrics) }
        try {
            while (true) {
                runCatching { sample() }.onFailure { ensureActive(); log.warn("backlog sampling failed", it) }
                delay(interval)
            }
        } finally {
            gauges.forEach(metrics::remove)
        }
    }
}
