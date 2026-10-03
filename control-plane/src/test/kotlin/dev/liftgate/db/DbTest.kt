package dev.liftgate.db

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.liftgate.TestDatabase
import dev.liftgate.testConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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

    @Test
    fun `the connection pool reports to the metrics registry`() {
        val metrics = SimpleMeterRegistry()
        Db(TestDatabase.config, metrics).use { assertEquals(2.0, metrics.get("hikaricp.connections.min").gauge().value()) }
    }

    @Test
    fun `migrating waits for a database that is not up yet`() {
        val port = ServerSocket(0).use { it.localPort }
        val config = testConfig(
            mapOf(
                "LIFTGATE_ROLE" to "migrate",
                "LIFTGATE_DATABASE_URL" to "jdbc:postgresql://localhost:$port/test",
                "LIFTGATE_DATABASE_USER" to "test",
                "LIFTGATE_DATABASE_PASSWORD" to "test",
            ),
        )
        val migrated = CompletableFuture.runAsync { Db(config).use { it.migrate() } }
        PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine")).apply { portBindings = listOf("$port:5432") }.use {
            it.start()
            migrated.get(2, TimeUnit.MINUTES)
        }
    }
}
