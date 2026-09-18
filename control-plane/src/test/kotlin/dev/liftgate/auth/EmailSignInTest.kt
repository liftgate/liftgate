package dev.liftgate.auth

import dev.liftgate.cache.Cache
import dev.liftgate.config.EmailConfig
import dev.liftgate.db.Db
import dev.liftgate.db.EmailCodes as EmailCodesTable
import dev.liftgate.db.Identities
import dev.liftgate.db.Users
import dev.liftgate.db.now
import dev.liftgate.http.LiftgateException
import dev.liftgate.testConfig
import io.mockk.coEvery
import io.mockk.mockk
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/18/2026
 */
@Testcontainers(disabledWithoutDocker = true)
class EmailSignInTest {
    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))

        private val cache by lazy { Cache(testConfig()) }

        @AfterAll
        @JvmStatic
        fun close() = cache.close()
    }

    private val config by lazy {
        testConfig(
            mapOf(
                "LIFTGATE_DATABASE_URL" to postgres.jdbcUrl,
                "LIFTGATE_DATABASE_USER" to postgres.username,
                "LIFTGATE_DATABASE_PASSWORD" to postgres.password,
            ),
        )
    }
    private val db by lazy { Db(config).also { it.migrate() } }
    private val sent = mutableListOf<MimeMessage>()
    private val codes by lazy {
        EmailCodes(
            db,
            cache,
            config.secretsMasterKey,
            Mailer(EmailConfig("mail.example.com", 587, false, null, null, "login@liftgate.dev")) { sent += it },
            SignIn(db, mockk<Sessions> { coEvery { create(any()) } returns "session" }),
        )
    }
    private val address = "${UUID.randomUUID()}@example.com"

    private suspend fun start() = codes.start(address, UUID.randomUUID().toString()).let {
        Regex("\\d{6}").find((sent.last().content as MimeMultipart).getBodyPart(0).content as String)!!.value
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
