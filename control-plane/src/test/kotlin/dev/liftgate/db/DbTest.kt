package dev.liftgate.db

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.liftgate.TestDatabase
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Dean
 * @date 9/27/2026
 */
class DbTest {
    @Test
    fun `migrating logs no flyway warnings`() {
        val flyway = LoggerFactory.getLogger("org.flywaydb") as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        flyway.addAppender(events)
        try {
            TestDatabase.clean().migrate()
        } finally {
            flyway.detachAppender(events)
        }
        assertEquals(emptyList(), events.list.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { it.formattedMessage })
    }
}
