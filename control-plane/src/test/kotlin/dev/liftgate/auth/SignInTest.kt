package dev.liftgate.auth

import dev.liftgate.TestDatabase
import dev.liftgate.config.Signup
import dev.liftgate.db.AuditLog
import dev.liftgate.db.Identities
import dev.liftgate.db.Organizations
import dev.liftgate.db.Users
import dev.liftgate.db.now
import dev.liftgate.http.LiftgateException
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * @author Dean
 * @date 9/18/2026
 */
class SignInTest {
    private val db = TestDatabase.clean()
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

    private fun withSignup(signup: Signup, allow: List<String> = emptyList(), consent: Boolean = false) =
        SignIn(db, mockk<Sessions> { coEvery { create(any()) } returns "session" }, signup, allow, consent)

    private suspend fun status(userId: UUID) = db.tx { Users.selectAll().where { Users.id eq userId }.single()[Users.status] }

    private suspend fun setStatus(userId: UUID, status: String) = db.tx { Users.update({ Users.id eq userId }) { it[Users.status] = status } }

    @Test
    fun `approval mode lets first-time github, saml and email users in as pending and open mode as active`() = runBlocking {
        val approval = withSignup(Signup.APPROVAL)
        listOf(
            VerifiedIdentity(GITHUB, "p1", null, false, login = "octo"),
            VerifiedIdentity(SAML, "connection:dean", "dean@acme.dev", false),
            VerifiedIdentity(EMAIL, "dean@example.dev", "dean@example.dev", true),
        ).forEach { assertEquals("pending", status(approval.complete(it).userId), it.provider) }
        assertEquals("active", status(withSignup(Signup.OPEN).complete(identity("p2", null, false)).userId))
    }

    @Test
    fun `closed mode inserts no user but still signs existing users in`() = runBlocking {
        val closed = withSignup(Signup.CLOSED)
        val error = assertFailsWith<LiftgateException> { closed.complete(identity("c1", "new@example.dev", true)) }
        assertEquals("signup_closed" to HttpStatusCode.Forbidden, error.code to error.status)
        assertEquals(0L to 0L, db.tx { Users.selectAll().count() to Identities.selectAll().count() })
        val existing = db.tx { insertUser("dean", null, "dean@example.dev", null) }.id
        assertEquals(existing, closed.complete(identity("c2", "dean@example.dev", true)).userId)
    }

    @Test
    fun `the allowlist activates github logins, verified emails and email domains on first sign-in`() = runBlocking {
        val allowing = withSignup(Signup.APPROVAL, listOf("github:dean", "ops@example.dev", "@acme.dev"))
        assertEquals("active", status(allowing.complete(VerifiedIdentity(GITHUB, "a1", null, false, login = "Dean")).userId))
        assertEquals("active", status(allowing.complete(identity("a2", "Ops@Example.dev", true)).userId))
        assertEquals("active", status(allowing.complete(identity("a3", "anyone@acme.dev", true)).userId))
        assertEquals("pending", status(allowing.complete(identity("a4", "someone@acme.dev", false)).userId))
        assertEquals("pending", status(allowing.complete(VerifiedIdentity("gitlab", "a5", null, false, login = "dean")).userId))
    }

    @Test
    fun `saml users vouched for by an organization with an active owner are active`() = runBlocking {
        val owner = db.tx { insertUser("owner", null, null, null) }.id
        val org = Orgs(db).create("acme", "Acme", owner)
        val approval = withSignup(Signup.APPROVAL)
        suspend fun jit(name: String) = status(approval.complete(VerifiedIdentity(SAML, "connection:$name", "$name@acme.dev", true, org = org.id)).userId)
        assertEquals("active", jit("first"))
        setStatus(owner, "pending")
        assertEquals("pending", jit("second"))
        setStatus(owner, "active")
        db.tx { Organizations.update({ Organizations.id eq org.id }) { it[suspendedAt] = now() } }
        assertEquals("pending", jit("third"))
    }

    @Test
    fun `new users record terms consent only when legal urls are configured`() = runBlocking {
        val consenting = withSignup(Signup.OPEN, consent = true).complete(identity("t1", null, false)).userId
        val silent = withSignup(Signup.OPEN).complete(identity("t2", null, false)).userId
        fun acceptedAt(userId: UUID) = Users.selectAll().where { Users.id eq userId }.single()[Users.termsAcceptedAt]
        db.tx {
            assertNotNull(acceptedAt(consenting))
            assertNull(acceptedAt(silent))
        }
    }

    @Test
    fun `a suspended user cannot sign in again`() = runBlocking {
        val open = withSignup(Signup.OPEN)
        val userId = open.complete(identity("s1", null, false)).userId
        setStatus(userId, "suspended")
        assertEquals("account_suspended", assertFailsWith<LiftgateException> { open.complete(identity("s1", null, false)) }.code)
        assertEquals("account_suspended", assertFailsWith<LiftgateException> { open.complete(userId, "passkey") }.code)
    }
}
