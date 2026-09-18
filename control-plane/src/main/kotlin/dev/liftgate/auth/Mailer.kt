package dev.liftgate.auth

import dev.liftgate.config.EmailConfig
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart

private const val TIMEOUT_MILLIS = "10000"

/**
 * @author Dean
 * @date 9/18/2026
 */
class Mailer(config: EmailConfig, private val deliver: (MimeMessage) -> Unit = Transport::send) {
    private val protocol = if (config.implicitTls) "smtps" else "smtp"
    private val session = Session.getInstance(
        mapOf(
            "mail.transport.protocol.rfc822" to protocol,
            "mail.$protocol.host" to config.host,
            "mail.$protocol.port" to config.port.toString(),
            "mail.$protocol.auth" to (config.user != null).toString(),
            "mail.$protocol.ssl.checkserveridentity" to "true",
            "mail.$protocol.connectiontimeout" to TIMEOUT_MILLIS,
            "mail.$protocol.timeout" to TIMEOUT_MILLIS,
            "mail.$protocol.writetimeout" to TIMEOUT_MILLIS,
            "mail.smtp.starttls.enable" to "true",
            "mail.smtp.starttls.required" to "true",
        ).toProperties(),
        config.user?.let { user ->
            object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(user, config.password.orEmpty())
            }
        },
    )
    private val fromAddress = InternetAddress(config.from, true)

    fun sendCode(to: String, code: String, minutes: Long) = deliver(
        MimeMessage(session).apply {
            setFrom(fromAddress)
            setRecipient(Message.RecipientType.TO, InternetAddress(to, true))
            setSubject("Your Liftgate sign-in code", "UTF-8")
            setContent(
                MimeMultipart(
                    "alternative",
                    MimeBodyPart().apply {
                        setText("Your Liftgate sign-in code is $code.\n\nIt expires in $minutes minutes.\n\nIf you did not request this code, you can ignore this email.\n", "UTF-8")
                    },
                    MimeBodyPart().apply {
                        setText(
                            """<p>Your Liftgate sign-in code is</p><p style="font-size:24px;font-weight:600;letter-spacing:4px">$code</p><p>It expires in $minutes minutes.</p><p>If you did not request this code, you can ignore this email.</p>""",
                            "UTF-8",
                            "html",
                        )
                    },
                ),
            )
        },
    )
}
