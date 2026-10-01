package dev.liftgate

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.status.Status
import dev.liftgate.deploy.Deployments
import dev.liftgate.events.Subject
import dev.liftgate.http.liftgate
import dev.liftgate.k8s.Reconciler
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.sql.SQLException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * @author Dean
 * @date 9/30/2026
 */
class LogFormatTest {
    private val logback = LoggerFactory.getILoggerFactory() as LoggerContext
    private val stdout = System.out
    private val out = ByteArrayOutputStream()

    private fun configure(format: String?) {
        logback.reset()
        format?.let { logback.putProperty("LIFTGATE_LOG_FORMAT", it) }
        val since = System.currentTimeMillis()
        JoranConfigurator().apply { context = logback }.doConfigure(javaClass.getResource("/logback.xml"))
        assertEquals(emptyList(), logback.statusManager.copyOfStatusList.filter { it.timestamp >= since && it.level >= Status.WARN }.map { it.message })
    }

    private fun lines(): List<JsonObject> = out.toString().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    private suspend fun logged(match: (JsonObject) -> Boolean) = withTimeout(30.seconds) {
        var line = lines().firstOrNull(match)
        while (line == null) {
            delay(100)
            line = lines().firstOrNull(match)
        }
        line
    }

    private val JsonObject.mdc get() = get("mdc")?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }

    @BeforeTest
    fun json() {
        configure("json")
        System.setOut(PrintStream(out, true))
    }

    @AfterTest
    fun text() {
        System.setOut(stdout)
        configure(null)
    }

    @Test
    fun `a failed release logs one json line carrying its deployment id and stack trace`() = runBlocking {
        val deploymentId = UUID.randomUUID()
        val nats = TestNats.clean()
        val consumers = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        val app = mockk<App> {
            every { this@mockk.nats } returns nats
            every { scope } returns consumers
            every { deployments } returns mockk<Deployments> { coEvery { byId(deploymentId) } throws SQLException("the database is unreachable") }
        }
        Reconciler(app, mockk()).start()
        nats.publish(Subject.RELEASE_REQUESTED.value, 1, buildJsonObject { put("deploymentId", deploymentId.toString()) })
        val failure = logged { it.mdc["deploymentId"] == deploymentId.toString() }
        consumers.cancel()
        assertEquals("WARN", failure["level"]?.jsonPrimitive?.content)
        assertEquals("handler failed for liftgate.release.requested", failure["formattedMessage"]?.jsonPrimitive?.content)
        assertTrue("the database is unreachable" in failure["throwable"].toString())
    }

    @Test
    fun `every request logs one json line carrying its call id`() = testApplication {
        application { liftgate(mockk(relaxed = true) { every { config } returns testConfig(); every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/orgs").status)
        val call = logged { "/api/v1/orgs" in it["formattedMessage"].toString() }
        assertTrue(call["formattedMessage"]?.jsonPrimitive?.content.orEmpty().startsWith("401 Unauthorized: GET - /api/v1/orgs"), call.toString())
        assertTrue(call.mdc["callId"].orEmpty().isNotBlank(), call.toString())
    }
}
