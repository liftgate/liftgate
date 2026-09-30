package dev.liftgate.admin

import dev.liftgate.TestDatabase
import dev.liftgate.auth.GITHUB
import dev.liftgate.auth.Mailer
import dev.liftgate.auth.Sessions
import dev.liftgate.auth.SignIn
import dev.liftgate.auth.VerifiedIdentity
import dev.liftgate.config.EmailConfig
import dev.liftgate.config.Signup
import dev.liftgate.db.AuditLog
import dev.liftgate.db.GitConnections
import dev.liftgate.db.Identities
import dev.liftgate.db.Outbox
import dev.liftgate.events.Subject
import dev.liftgate.events.uuid
import dev.liftgate.org.insertUser
import io.mockk.coEvery
import io.mockk.mockk
import jakarta.mail.Message
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/30/2026
 */
class OperatorTest {
    private val db = TestDatabase.clean()
    private val sent = mutableListOf<MimeMessage>()
    private val mailer = Mailer(EmailConfig("mail.example.com", 587, false, null, null, "login@liftgate.dev")) { sent += it }
    private val operators = listOf("github:octo-ops", "ops@example.com")
    private val admin = Admin(db, mailer = mailer, operators = operators, dashboardUrl = "https://liftgate.example.com")
    private val sessions = mockk<Sessions> { coEvery { create(any()) } returns "session" }

    private suspend fun pendingSignUps() = db.tx { Outbox.selectAll().where { Outbox.subject eq Subject.USER_PENDING.value }.map { it[Outbox.payload].uuid("userId") } }

    @Test
    fun `a pending sign-up emails every operator with a verified email, and nothing is sent without mail`() = runBlocking {
        val dean = db.tx { insertUser("dean", null, "dean@example.com", null) }.id
        db.tx {
            GitConnections.insert {
                it[userId] = dean
                it[provider] = GITHUB
                it[accountLogin] = "Octo-Ops"
                it[accessToken] = byteArrayOf(1)
            }
        }
        val ops = db.tx { insertUser("ops", null, "ops@example.com", null) }.id
        db.tx {
            Identities.insert {
                it[id] = UUID.randomUUID()
                it[userId] = ops
                it[provider] = "email"
                it[subject] = "ops@example.com"
                it[email] = "ops@example.com"
                it[emailVerified] = true
            }
        }
        db.tx { insertUser("bystander", null, "bystander@example.com", null) }
        assertEquals(listOf(true, false), listOf(dean, ops).map { Admin(db, operators = listOf("github:octo-ops")).isOperator(it) })
        assertEquals(listOf(false, true), listOf(dean, ops).map { Admin(db, operators = listOf("ops@example.com")).isOperator(it) })
        SignIn(db, sessions, Signup.OPEN).complete(VerifiedIdentity(GITHUB, "1", "open@example.dev", true, login = "open"))
        assertEquals(emptyList(), pendingSignUps())
        val newbie = SignIn(db, sessions, Signup.APPROVAL).complete(VerifiedIdentity(GITHUB, "2", "newbie@example.dev", true, login = "newbie")).userId
        assertEquals(listOf(newbie), pendingSignUps())

        Admin(db, operators = operators).announce(newbie)
        Admin(db, mailer = mailer).announce(newbie)
        assertEquals(emptyList(), sent)

        admin.announce(newbie)
        assertEquals(setOf("dean@example.com", "ops@example.com"), sent.map { it.getRecipients(Message.RecipientType.TO).single().toString() }.toSet())
        assertEquals(setOf("A Liftgate account is waiting for approval"), sent.map { it.subject }.toSet())
        assertTrue(sent.all { "newbie (newbie@example.dev)" in it.content as String && "https://liftgate.example.com/dashboard/operator" in it.content as String })

        admin.approve(newbie.toString(), dean)
        sent.clear()
        admin.announce(newbie)
        assertEquals(emptyList(), sent)
        assertEquals(listOf(dean), db.tx { AuditLog.selectAll().where { AuditLog.action eq "user.approve" }.map { it[AuditLog.actorUserId] } })
    }
}
