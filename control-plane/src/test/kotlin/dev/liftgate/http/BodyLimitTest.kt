package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.config.GitHubConfig
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MIB = 1024 * 1024

/**
 * @author Dean
 * @date 9/27/2026
 */
class BodyLimitTest {
    private val app = mockk<App> {
        every { config } returns testConfig().copy(github = GitHubConfig("1", "unused", "webhook", "client", "client-secret"))
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { cache } returns unlimitedCache
        every { passkeys } returns mockk()
    }

    private fun stream(size: Long, declared: Boolean) = object : OutgoingContent.WriteChannelContent() {
        var written = 0L
        override val contentLength = size.takeIf { declared }
        override val contentType = ContentType.Application.Json
        override suspend fun writeTo(channel: ByteWriteChannel) {
            val chunk = ByteArray(64 * 1024) { ' '.code.toByte() }
            while (written < size) channel.writeFully(chunk).also { written += chunk.size }
        }
    }

    private suspend fun HttpResponse.error() = json.decodeFromString(ErrorBody.serializer(), bodyAsText()).error

    @Test
    fun `a webhook over the github cap is refused before it is read`() = testApplication {
        application { liftgate(app) }
        val body = stream(30L * MIB, declared = true)
        val response = client.post("/api/v1/webhooks/github") { setBody(body) }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("payload_too_large", response.error())
        assertTrue(body.written < WEBHOOK_BODY_LIMIT, "${body.written} bytes were read")
    }

    @Test
    fun `a webhook under the github cap reaches the signature check`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/webhooks/github") { setBody(ByteArray(20 * MIB)) }.status)
    }

    @Test
    fun `a 2 MiB json body to an anonymous route is refused`() = testApplication {
        application { liftgate(app) }
        val response = client.post("/api/v1/auth/passkey/options") {
            contentType(ContentType.Application.Json)
            setBody(ByteArray(2 * MIB) { ' '.code.toByte() })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("payload_too_large", response.error())
    }

    @Test
    fun `a streamed json body is cut off at the cap`() = testApplication {
        application { liftgate(app) }
        val response = client.post("/api/v1/auth/passkey/verify") { setBody(stream(2L * MIB, declared = false)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("payload_too_large", response.error())
    }
}
