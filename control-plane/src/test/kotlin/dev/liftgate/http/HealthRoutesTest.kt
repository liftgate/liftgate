package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.db.Db
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class HealthRoutesTest {
    private val db = mockk<Db>()
    private val app = mockk<App>().also {
        every { it.db } returns db
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.config } returns testConfig()
    }

    @Test
    fun `healthz is always ok`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
    }

    @Test
    fun `readyz pings the database`() = testApplication {
        coEvery { db.tx(any<JdbcTransaction.() -> Any?>()) } returns true
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
    fun `metrics are exposed in prometheus format`() = testApplication {
        application { liftgate(app) }
        assertTrue(client.get("/metrics").bodyAsText().contains("jvm_"))
    }
}
