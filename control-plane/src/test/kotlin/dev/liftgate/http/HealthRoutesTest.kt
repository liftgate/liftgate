package dev.liftgate.http

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.liftgate.App
import dev.liftgate.cache.Cache
import dev.liftgate.config.Role
import dev.liftgate.db.Db
import dev.liftgate.events.Nats
import dev.liftgate.testConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class HealthRoutesTest {
    private val db = mockk<Db> { coEvery { tx(any<JdbcTransaction.() -> Any?>()) } returns true }
    private val polls = mutableMapOf<String, Instant>()
    private val nats = mockk<Nats> {
        every { lastPolls } returns polls
    }
    private val app = mockk<App>().also {
        every { it.db } returns db
        every { it.nats } returns nats
        every { it.stopping } returns false
        every { it.runs(any()) } returns false
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.config } returns testConfig()
    }

    @Test
    fun `healthz is ok while every consumer polls`() = testApplication {
        polls["reconciler-release-requested"] = Instant.now()
        application { liftgate(app) }
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
    }

    @Test
    fun `healthz fails once a consumer has not polled for two minutes`() = testApplication {
        polls["reconciler-release-requested"] = Instant.now()
        polls["builder-build-requested"] = Instant.now() - Duration.ofMinutes(3)
        application { liftgate(app) }
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("not polling: builder-build-requested", response.bodyAsText())
    }

    @Test
    fun `readyz pings the database`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.OK, client.get("/readyz").status)
    }

    @Test
    fun `readyz fails when the database is unreachable`() = testApplication {
        coEvery { db.tx(any<JdbcTransaction.() -> Any?>()) } throws IllegalStateException("connection refused")
        application { liftgate(app) }
        val response = client.get("/readyz")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("""{"error":"internal_error","message":"internal error"}""", response.bodyAsText())
    }

    @Test
    fun `readyz fails while stopping or without hazelcast where the api runs`() = testApplication {
        application { liftgate(app) }
        suspend fun status() = client.get("/readyz").status
        every { app.stopping } returns true
        assertEquals(HttpStatusCode.ServiceUnavailable, status())
        every { app.stopping } returns false
        val cache = mockk<Cache> { every { running } returns false }
        every { app.runs(Role.API) } returns true
        every { app.cache } returns cache
        assertEquals(HttpStatusCode.ServiceUnavailable, status())
        every { cache.running } returns true
        assertEquals(HttpStatusCode.OK, status())
    }

    @Test
    fun `metrics are exposed in prometheus format`() = testApplication {
        application { liftgate(app) }
        assertTrue(client.get("/metrics").bodyAsText().contains("jvm_"))
    }

    @Test
    fun `request metrics add no meter filter to a registry that already has meters, and keep their percentiles`() = testApplication {
        val registry = LoggerFactory.getLogger(PrometheusMeterRegistry::class.java) as Logger
        val warnings = ListAppender<ILoggingEvent>().apply { start() }
        registry.addAppender(warnings)
        app.metrics.counter("hikaricp.connections.timeout")
        try {
            application { liftgate(app) }
            assertEquals(HttpStatusCode.OK, client.get("/readyz").status)
        } finally {
            registry.detachAppender(warnings)
        }
        assertEquals(emptyList(), warnings.list.map { it.formattedMessage })
        assertTrue(client.get("/metrics").bodyAsText().lines().any { it.startsWith("ktor_http_server_requests_seconds{") && "route=\"/readyz\"" in it && "quantile=\"0.99\"" in it })
    }

    @Test
    fun `roles without the api serve only health routes`() = testApplication {
        application { health(app) }
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        assertEquals(HttpStatusCode.OK, client.get("/readyz").status)
        assertTrue(client.get("/metrics").bodyAsText().contains("jvm_"))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/me").status)
    }
}
