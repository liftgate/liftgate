package dev.liftgate.auth

import dev.liftgate.config.EmailConfig
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

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

    fun send(to: String, subject: String, text: String) = deliver(
        MimeMessage(session).apply {
            setFrom(fromAddress)
            setRecipient(Message.RecipientType.TO, InternetAddress(to, true))
            setSubject(subject, "UTF-8")
            setText(text, "UTF-8")
        },
    )
}
