package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.EmailCodes
import dev.liftgate.auth.Mailer
import dev.liftgate.auth.OAuth
import dev.liftgate.auth.SignIn
import dev.liftgate.auth.SignedIn
import dev.liftgate.auth.STARTS_PER_EMAIL
import dev.liftgate.auth.STARTS_PER_IP
import dev.liftgate.cache.Cache
import dev.liftgate.config.EmailConfig
import dev.liftgate.db.Db
import dev.liftgate.testConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.AfterAll
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/18/2026
 */
class EmailRoutesTest {
    companion object {
        private val cache = Cache(testConfig())

        @AfterAll
        @JvmStatic
        fun close() = cache.close()
    }

    private val sent = mutableListOf<MimeMessage>()
    private val signIn = mockk<SignIn>()
    private val codes = EmailCodes(
        mockk<Db> { coEvery { tx<Any?>(any()) } returns null },
        cache,
        Random.nextBytes(32),
        Mailer(EmailConfig("mail.example.com", 587, false, null, null, "login@liftgate.dev")) { sent += it },
        signIn,
    )

    private fun app(emailCodes: EmailCodes?) = mockk<App>().also {
        every { it.config } returns testConfig(mapOf("LIFTGATE_TRUSTED_PROXIES" to "1"))
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.oauth } returns OAuth(mockk(), "http://localhost:8080", emptyList())
        every { it.emailCodes } returns emailCodes
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, ip: String = UUID.randomUUID().toString()): HttpResponse =
        client.post("/api/v1/auth/email/$path") {
            header(HttpHeaders.XForwardedFor, "${UUID.randomUUID()}, $ip")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `providers report email only when it is configured`() = testApplication {
        application { liftgate(app(codes)) }
        assertTrue(""""email":true""" in client.get("/api/v1/auth/providers").bodyAsText())
    }

    @Test
    fun `start answers 204 for an address nobody has used`() = testApplication {
        application { liftgate(app(codes)) }
        assertEquals(HttpStatusCode.NoContent, post("start", """{"email":"${UUID.randomUUID()}@example.com"}""").status)
        assertEquals(1, sent.size)
    }

    @Test
    fun `too many starts answer 429 rate_limited`() = testApplication {
        application { liftgate(app(codes)) }
        val body = """{"email":"${UUID.randomUUID()}@example.com"}"""
        repeat(STARTS_PER_EMAIL) { assertEquals(HttpStatusCode.NoContent, post("start", body).status) }
        val limited = post("start", body)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("rate_limited", json.decodeFromString(ErrorBody.serializer(), limited.bodyAsText()).error)
    }

    @Test
    fun `the ip limit keys on the address the trusted proxy appended`() = testApplication {
        application { liftgate(app(codes)) }
        val ip = UUID.randomUUID().toString()
        suspend fun start() = post("start", """{"email":"${UUID.randomUUID()}@example.com"}""", ip)
        repeat(STARTS_PER_IP) { assertEquals(HttpStatusCode.NoContent, start().status) }
        assertEquals(HttpStatusCode.TooManyRequests, start().status)
    }

    @Test
    fun `a verified code starts a session`() = testApplication {
        val emailCodes = mockk<EmailCodes> { coEvery { verify("dean@liftgate.dev", "123456") } returns SignedIn(UUID.randomUUID(), "session-3") }
        application { liftgate(app(emailCodes)) }
        val response = post("verify", """{"email":"dean@liftgate.dev","code":"123456"}""")
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(response.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$SESSION_COOKIE=session-3") })
    }

    @Test
    fun `email routes are absent when email is not configured`() = testApplication {
        application { liftgate(app(null)) }
        assertEquals(HttpStatusCode.NotFound, post("start", """{"email":"dean@liftgate.dev"}""").status)
    }
}
