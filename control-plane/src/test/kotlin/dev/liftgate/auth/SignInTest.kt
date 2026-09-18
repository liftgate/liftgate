package dev.liftgate.auth

import dev.liftgate.db.AuditLog
import dev.liftgate.db.Db
import dev.liftgate.http.LiftgateException
import dev.liftgate.org.insertUser
import dev.liftgate.testConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * @author Dean
 * @date 9/18/2026
 */
@Testcontainers(disabledWithoutDocker = true)
class SignInTest {
    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
    }

    private val db by lazy {
        Db(
            testConfig(
                mapOf(
                    "LIFTGATE_DATABASE_URL" to postgres.jdbcUrl,
                    "LIFTGATE_DATABASE_USER" to postgres.username,
                    "LIFTGATE_DATABASE_PASSWORD" to postgres.password,
                ),
            ),
        ).also { it.migrate() }
    }
    private val signIn by lazy { SignIn(db, mockk<Sessions> { coEvery { create(any()) } returns "session" }) }

    private fun identity(subject: String, email: String?, verified: Boolean, provider: String = "google") =
        VerifiedIdentity(provider, subject, email, verified, name = "Dean")

    @Test
    fun `identities resolve, verified emails link and unverified emails never do`() = runBlocking {
        val owner = db.tx { insertUser("owner", null, "Owner@Example.dev", null) }.id
        val first = signIn.complete(identity("g1", "fresh@example.dev", true)).userId
        assertEquals(first, signIn.complete(identity("g1", "changed@example.dev", false)).userId)
        assertEquals(owner, signIn.complete(identity("g2", "owner@example.dev", true)).userId)
        assertNotEquals(owner, signIn.complete(identity("g3", "owner@example.dev", false)).userId)
        assertEquals(2L, db.tx { AuditLog.selectAll().where { AuditLog.actorUserId eq first }.count() })
    }

    @Test
    fun `linking refuses an identity owned by someone else`() = runBlocking {
        val dean = signIn.complete(identity("l1", null, false)).userId
        val other = signIn.complete(identity("l2", null, false)).userId
        signIn.link(dean, identity("l3", null, false, provider = "gitlab"))
        assertEquals(listOf("google", "gitlab"), signIn.identities(dean).map { it.provider })
        assertEquals("identity_in_use", assertFailsWith<LiftgateException> { signIn.link(dean, identity("l2", null, false)) }.code)
        assertEquals(1, signIn.identities(other).size)
    }

    @Test
    fun `the last sign-in method cannot be removed`() = runBlocking {
        val userId = signIn.complete(identity("r1", null, false)).userId
        signIn.link(userId, identity("r2", null, false, provider = "bitbucket"))
        val (first, second) = signIn.identities(userId)
        signIn.unlink(userId, first.id)
        assertEquals("last_method", assertFailsWith<LiftgateException> { signIn.unlink(userId, second.id) }.code)
        assertEquals(listOf(second.id), signIn.identities(userId).map { it.id })
    }
}
