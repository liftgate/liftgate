package dev.liftgate.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

private const val OUTBOX_RETENTION_DAYS = 7L

/**
 * @author Dean
 * @date 9/27/2026
 */
class Housekeeping(private val db: Db) {
    private val log = LoggerFactory.getLogger(Housekeeping::class.java)

    suspend fun runOnce() {
        db.tx {
            Outbox.deleteWhere { publishedAt less now().minusDays(OUTBOX_RETENTION_DAYS) }
            Sessions.deleteWhere { expiresAt less now() }
            EmailCodes.deleteWhere { expiresAt less now() }
        }
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            runCatching { runOnce() }.onFailure { ensureActive(); log.warn("housekeeping failed", it) }
            delay(1.hours)
        }
    }
}
