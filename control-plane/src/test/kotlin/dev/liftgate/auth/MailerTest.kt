package dev.liftgate.auth

import dev.liftgate.config.EmailConfig
import dev.liftgate.testConfig
import jakarta.mail.Message
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/18/2026
 */
class MailerTest {
    private fun email(url: String?, from: String? = "Liftgate <login@liftgate.dev>") =
        testConfig(listOfNotNull(url?.let { "LIFTGATE_SMTP_URL" to it }, from?.let { "LIFTGATE_EMAIL_FROM" to it }).toMap()).email

    @Test
    fun `smtp urls use starttls on 587 and decode their credentials`() {
        assertEquals(
            EmailConfig("mail.example.com", 587, false, "login@liftgate.dev", "p@ss:w+rd/", "Liftgate <login@liftgate.dev>"),
            email("smtp://login%40liftgate.dev:p%40ss%3Aw+rd%2F@mail.example.com"),
        )
        assertEquals(2525, email("smtp://mail.example.com:2525")?.port)
        assertNull(email("smtp://mail.example.com")?.user)
    }

    @Test
    fun `smtps urls use implicit tls on 465`() {
        assertEquals(EmailConfig("mail.example.com", 465, true, "apikey", "s3cret", "Liftgate <login@liftgate.dev>"), email("smtps://apikey:s3cret@mail.example.com"))
        assertEquals(2465, email("smtps://mail.example.com:2465")?.port)
    }

    @Test
    fun `email needs both variables and a supported scheme`() {
        assertNull(email("smtp://mail.example.com", from = null))
        assertNull(email(null))
        assertTrue("LIFTGATE_SMTP_URL" in assertFailsWith<IllegalStateException> { email("http://mail.example.com") }.message.orEmpty())
    }

    @Test
    fun `the code email has a plain text and an html part`() {
        val sent = mutableListOf<MimeMessage>()
        Mailer(email("smtp://user:pass@mail.example.com")!!) { sent += it }.sendCode("dean@liftgate.dev", "042917", 10)
        val message = sent.single().apply { saveChanges() }
        assertEquals("Your Liftgate sign-in code", message.subject)
        assertEquals("dean@liftgate.dev", message.getRecipients(Message.RecipientType.TO).single().toString())
        assertEquals("Liftgate <login@liftgate.dev>", message.from.single().toString())
        val parts = message.content as MimeMultipart
        val text = parts.getBodyPart(0).content as String
        assertTrue(parts.getBodyPart(0).isMimeType("text/plain") && parts.getBodyPart(1).isMimeType("text/html"))
        listOf("042917", "expires in 10 minutes", "did not request").forEach { assertTrue(it in text, it) }
        assertTrue("042917" in parts.getBodyPart(1).content as String)
        assertEquals("true", message.session.getProperty("mail.smtp.starttls.required"))
    }

    @Test
    fun `smtps sessions send over the smtps transport`() {
        val sent = mutableListOf<MimeMessage>()
        Mailer(email("smtps://mail.example.com")!!) { sent += it }.sendCode("dean@liftgate.dev", "000001", 10)
        val session = sent.single().session
        assertEquals("smtps", session.getProperty("mail.transport.protocol.rfc822"))
        assertEquals("465", session.getProperty("mail.smtps.port"))
        assertEquals("false", session.getProperty("mail.smtps.auth"))
    }
}
