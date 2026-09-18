package dev.liftgate.auth

import dev.liftgate.cache.Cache
import dev.liftgate.config.EmailConfig
import dev.liftgate.db.Db
import dev.liftgate.http.LiftgateException
import dev.liftgate.testConfig
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import jakarta.mail.Message
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/18/2026
 */
class EmailCodesTest {
    companion object {
        private val cache = Cache(testConfig())

        @AfterAll
        @JvmStatic
        fun close() = cache.close()
    }

    private val sent = mutableListOf<MimeMessage>()
    private val mailer = Mailer(EmailConfig("mail.example.com", 587, false, null, null, "login@liftgate.dev")) { sent += it }
    private val db = mockk<Db> { coEvery { tx<Any?>(any()) } returns null }
    private val codes = EmailCodes(db, cache, Random.nextBytes(32), mailer, mockk())
    private val stored = codes.hash("dean@liftgate.dev", "123456")
    private val later = Instant.now().plusSeconds(60)

    private fun ip() = "10.0.${Random.nextInt(256)}.${Random.nextInt(256)}-${UUID.randomUUID()}"

    private fun MimeMessage.code() = Regex("\\d{6}").find((content as MimeMultipart).getBodyPart(0).content as String)!!.value

    @Test
    fun `codes are stored as a keyed hash bound to the email`() {
        assertEquals(32, stored.size)
        assertContentEquals(stored, codes.hash("dean@liftgate.dev", "123456"))
        assertFalse(stored.contentEquals(codes.hash("dean@liftgate.dev", "123457")))
        assertFalse(stored.contentEquals(codes.hash("eve@liftgate.dev", "123456")))
        assertFalse(stored.contentEquals(EmailCodes(db, cache, Random.nextBytes(32), mailer, mockk()).hash("dean@liftgate.dev", "123456")))
    }

    @Test
    fun `only the exact hash matches`() {
        assertEquals(CodeCheck.MATCH, checkCode(stored, 0, later, codes.hash("dean@liftgate.dev", "123456")))
        assertEquals(CodeCheck.WRONG, checkCode(stored, 0, later, stored.copyOf().also { it[31] = (it[31] + 1).toByte() }))
        assertEquals(CodeCheck.WRONG, checkCode(stored, 0, later, stored.copyOf(16)))
    }

    @Test
    fun `an expired code is spent even when it is right`() {
        assertEquals(CodeCheck.SPENT, checkCode(stored, 0, Instant.now().minusSeconds(1), stored))
        assertEquals(CodeCheck.SPENT, checkCode(stored, 0, later, stored, now = later))
    }

    @Test
    fun `the fifth wrong attempt spends the code`() {
        val wrong = codes.hash("dean@liftgate.dev", "000000")
        (0 until MAX_ATTEMPTS - 1).forEach { assertEquals(CodeCheck.WRONG, checkCode(stored, it, later, wrong)) }
        assertEquals(CodeCheck.SPENT, checkCode(stored, MAX_ATTEMPTS - 1, later, wrong))
        assertEquals(CodeCheck.MATCH, checkCode(stored, MAX_ATTEMPTS - 1, later, stored))
    }

    @Test
    fun `starting mails a fresh six digit code to any address`() = runBlocking {
        val address = "${UUID.randomUUID()}@Example.com"
        codes.start("  $address ", ip())
        codes.start(address, ip())
        val (first, second) = sent
        assertEquals(address.lowercase(), first.getRecipients(Message.RecipientType.TO).single().toString())
        assertTrue(first.code().length == 6 && second.code().length == 6)
    }

    @Test
    fun `starts are limited per email`() = runBlocking {
        val address = "${UUID.randomUUID()}@example.com"
        repeat(STARTS_PER_EMAIL) { codes.start(address, ip()) }
        val error = assertFailsWith<LiftgateException> { codes.start(address.uppercase(), ip()) }
        assertEquals(HttpStatusCode.TooManyRequests to "rate_limited", error.status to error.code)
        assertEquals(STARTS_PER_EMAIL, sent.size)
    }

    @Test
    fun `starts are limited per client ip`() = runBlocking {
        val ip = ip()
        repeat(STARTS_PER_IP) { codes.start("${UUID.randomUUID()}@example.com", ip) }
        assertEquals("rate_limited", assertFailsWith<LiftgateException> { codes.start("${UUID.randomUUID()}@example.com", ip) }.code)
        codes.start("${UUID.randomUUID()}@example.com", ip())
        assertEquals(STARTS_PER_IP + 1, sent.size)
    }

    @Test
    fun `malformed addresses are rejected before anything is sent`() = runBlocking {
        listOf("", "dean", "dean@", "dean@localhost", "a b@example.com", "dean@example.com\r\nBcc: eve@example.com").forEach {
            assertEquals(HttpStatusCode.UnprocessableEntity, assertFailsWith<LiftgateException>(it) { codes.start(it, ip()) }.status)
        }
        assertTrue(sent.isEmpty())
    }
}
