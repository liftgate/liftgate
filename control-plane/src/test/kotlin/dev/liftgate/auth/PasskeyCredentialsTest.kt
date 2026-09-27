package dev.liftgate.auth

import com.yubico.webauthn.data.ByteArray as Bytes
import dev.liftgate.TestDatabase
import dev.liftgate.db.Passkeys as PasskeysTable
import dev.liftgate.http.LiftgateException
import dev.liftgate.testConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/18/2026
 */
class PasskeyCredentialsTest {
    private val db = TestDatabase.clean()
    private val signIn by lazy { SignIn(db, mockk<Sessions> { coEvery { create(any()) } returns "session" }) }
    private val passkeys by lazy { Passkeys(testConfig(), db, mockk(), signIn) }

    private suspend fun passkey(userId: UUID, signatureCount: Long = 0) = db.tx {
        Bytes(Random.nextBytes(16)).also { credential ->
            PasskeysTable.insert {
                it[id] = UUID.randomUUID()
                it[PasskeysTable.userId] = userId
                it[credentialId] = credential.bytes
                it[publicKey] = Random.nextBytes(77)
                it[PasskeysTable.signatureCount] = signatureCount
                it[name] = "laptop"
            }
        }
    }

    @Test
    fun `credentials resolve only for their owner and record the new counter`(): Unit = runBlocking {
        val owner = signIn.complete(VerifiedIdentity("google", "p1", null, false)).userId
        val credential = passkey(owner, signatureCount = 3)
        db.tx {
            assertEquals(3L, PasskeyCredentials.lookup(credential, owner.userHandle).get().signatureCount)
            assertFalse(PasskeyCredentials.lookup(credential, UUID.randomUUID().userHandle).isPresent)
            assertFalse(PasskeyCredentials.lookup(credential, Bytes(ByteArray(8))).isPresent)
            assertEquals(owner.toString(), PasskeyCredentials.getUsernameForUserHandle(owner.userHandle).get())
            assertEquals(setOf(credential), PasskeyCredentials.descriptors(owner).map { it.id }.toSet())
            assertEquals(owner, PasskeyCredentials.used(credential, 4))
            assertEquals(4L, PasskeyCredentials.lookupAll(credential).single().signatureCount)
        }
        assertNotNull(passkeys.list(owner).single().lastUsedAt)
    }

    @Test
    fun `passkeys count as sign-in methods`() = runBlocking {
        val userId = signIn.complete(VerifiedIdentity("google", "p2", null, false)).userId
        passkey(userId)
        val (identity) = signIn.identities(userId)
        signIn.unlink(userId, identity.id)
        val (remaining) = passkeys.list(userId)
        assertEquals("last_method", assertFailsWith<LiftgateException> { passkeys.delete(userId, remaining.id) }.code)
        passkey(userId)
        passkeys.delete(userId, remaining.id)
        assertTrue(passkeys.list(userId).none { it.id == remaining.id })
    }
}
