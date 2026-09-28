package dev.liftgate.auth

import dev.liftgate.TestDatabase
import dev.liftgate.cache.Cache
import dev.liftgate.config.EmailConfig
import dev.liftgate.db.EmailCodes as EmailCodesTable
import dev.liftgate.db.Identities
import dev.liftgate.db.Users
import dev.liftgate.db.now
import dev.liftgate.http.LiftgateException
import dev.liftgate.testConfig
import io.mockk.coEvery
import io.mockk.mockk
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/18/2026
 */
class EmailSignInTest {
    companion object {
        private val cache by lazy { Cache(testConfig()) }

        @AfterAll
        @JvmStatic
        fun close() = cache.close()
    }

    private val db = TestDatabase.clean()
    private val sent = mutableListOf<MimeMessage>()
    private val codes by lazy {
        EmailCodes(
            db,
            cache,
            checkNotNull(testConfig().secretsMasterKey),
            Mailer(EmailConfig("mail.example.com", 587, false, null, null, "login@liftgate.dev")) { sent += it },
            SignIn(db, mockk<Sessions> { coEvery { create(any()) } returns "session" }),
        )
    }
    private val address = "${UUID.randomUUID()}@example.com"

    private suspend fun start() = codes.start(address, UUID.randomUUID().toString()).let {
        Regex("\\d{6}").find(sent.last().content as String)!!.value
    }

    private fun wrong(code: String) = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    private suspend fun rejects(code: String, email: String = address) =
        assertEquals("invalid_code", assertFailsWith<LiftgateException> { codes.verify(email, code) }.code)

    @Test
    fun `a verified code signs in through an email identity once`() = runBlocking {
        val code = start()
        val userId = codes.verify(address.uppercase(), code).userId
        rejects(code)
        db.tx {
            val identity = Identities.selectAll().where { Identities.userId eq userId }.single()
            assertEquals(EMAIL to address, identity[Identities.provider] to identity[Identities.subject])
            assertTrue(identity[Identities.emailVerified])
            assertTrue(Users.selectAll().where { Users.id eq userId }.single()[Users.emailVerified])
            assertTrue(EmailCodesTable.selectAll().where { EmailCodesTable.email eq address }.empty())
        }
    }

    @Test
    fun `a newer code invalidates the older one`() = runBlocking {
        val first = start()
        val second = start()
        if (first != second) rejects(first)
        codes.verify(address, second)
        Unit
    }

    @Test
    fun `five wrong attempts spend the code`() = runBlocking {
        val code = start()
        repeat(MAX_ATTEMPTS) { rejects(wrong(code)) }
        rejects(code)
    }

    @Test
    fun `an expired code is rejected`() = runBlocking {
        val code = start()
        db.tx { EmailCodesTable.update({ EmailCodesTable.email eq address }) { it[expiresAt] = now().minusSeconds(1) } }
        rejects(code)
    }

    @Test
    fun `an address without a code is rejected`() = runBlocking {
        rejects("123456", "${UUID.randomUUID()}@example.com")
    }
}
