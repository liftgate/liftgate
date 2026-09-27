@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package dev.liftgate.auth

import dev.liftgate.config.Signup
import dev.liftgate.db.AuditLog
import dev.liftgate.db.Db
import dev.liftgate.db.Identities
import dev.liftgate.db.Memberships
import dev.liftgate.db.Organizations
import dev.liftgate.db.Passkeys as PasskeysTable
import dev.liftgate.db.Users
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.http.InstantSerializer
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.UuidSerializer
import dev.liftgate.http.notFound
import dev.liftgate.org.UserStatus
import dev.liftgate.org.insertUser
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.UUID

/**
 * @author Dean
 * @date 9/18/2026
 */
@Serializable
data class Identity(val id: UUID, val provider: String, val email: String?, val createdAt: Instant, val lastUsedAt: Instant?)

/**
 * @author Dean
 * @date 9/18/2026
 */
data class SignedIn(val userId: UUID, val sessionId: String)

private fun signInMethods(userId: UUID): Long = Identities.selectAll().where { Identities.userId eq userId }.count() +
    PasskeysTable.selectAll().where { PasskeysTable.userId eq userId }.count()

fun removeSignInMethod(userId: UUID, delete: () -> Int) {
    Users.select(Users.id).where { Users.id eq userId }.forUpdate(ForUpdateOption.ForUpdate).single()
    if (delete() == 0) notFound("sign-in method")
    if (signInMethods(userId) == 0L) throw LiftgateException(HttpStatusCode.Conflict, "last_method", "add another sign-in method before removing this one")
}

fun audit(userId: UUID, action: String, provider: String) = AuditLog.insert {
    it[actorUserId] = userId
    it[AuditLog.action] = action
    it[targetType] = "user"
    it[targetId] = userId.toString()
    it[details] = JsonObject(mapOf("provider" to JsonPrimitive(provider)))
}

/**
 * @author Dean
 * @date 9/18/2026
 */
class SignIn(
    private val db: Db,
    private val sessions: Sessions,
    private val signup: Signup = Signup.APPROVAL,
    private val allow: List<String> = emptyList(),
    private val consent: Boolean = false,
) {
    suspend fun complete(identity: VerifiedIdentity): SignedIn {
        val userId = db.tx {
            (owner(identity) ?: verifiedEmailOwner(identity) ?: createUser(identity)).also {
                refuseSuspended(it)
                confirmEmail(it, identity)
                remember(it, identity, "auth.signin")
            }
        }
        return SignedIn(userId, sessions.create(userId))
    }

    suspend fun complete(userId: UUID, provider: String): SignedIn {
        db.tx {
            refuseSuspended(userId)
            audit(userId, "auth.signin", provider)
        }
        return SignedIn(userId, sessions.create(userId))
    }

    suspend fun link(userId: UUID, identity: VerifiedIdentity) = db.tx {
        if (owner(identity).let { it != null && it != userId }) {
            throw LiftgateException(HttpStatusCode.Conflict, "identity_in_use", "this ${identity.provider} account belongs to another user")
        }
        remember(userId, identity, "auth.link")
    }

    suspend fun identities(userId: UUID): List<Identity> = db.tx {
        Identities.selectAll().where { Identities.userId eq userId }.orderBy(Identities.createdAt).map {
            Identity(it[Identities.id], it[Identities.provider], it[Identities.email], it[Identities.createdAt].toInstant(), it[Identities.lastUsedAt]?.toInstant())
        }
    }

    suspend fun unlink(userId: UUID, id: UUID) = db.tx {
        removeSignInMethod(userId) { Identities.deleteWhere { (Identities.id eq id) and (Identities.userId eq userId) } }
    }

    private fun owner(identity: VerifiedIdentity) = Identities.select(Identities.userId)
        .where { (Identities.provider eq identity.provider) and (Identities.subject eq identity.subject) }
        .singleOrNull()?.get(Identities.userId)

    private fun verifiedEmailOwner(identity: VerifiedIdentity) = identity.email?.takeIf { identity.emailVerified }?.let { email ->
        Users.select(Users.id).where { (Users.email.lowerCase() eq email.lowercase()) and (Users.emailVerified eq true) }.singleOrNull()?.get(Users.id)
    }

    private fun confirmEmail(userId: UUID, identity: VerifiedIdentity) = identity.email?.takeIf { identity.emailVerified && verifiedEmailOwner(identity) == null }?.let { email ->
        Users.update({ (Users.id eq userId) and (Users.email.lowerCase() eq email.lowercase()) }) { it[emailVerified] = true }
    }

    private fun createUser(identity: VerifiedIdentity): UUID {
        if (signup == Signup.CLOSED) throw LiftgateException(HttpStatusCode.Forbidden, "signup_closed", "sign-up is closed on this Liftgate instance")
        val active = signup == Signup.OPEN || allowed(identity) || identity.vouchedBy?.let(::vouches) == true
        return insertUser(
            identity.login ?: identity.email?.substringBefore('@') ?: identity.provider,
            identity.name,
            identity.email?.takeIf { identity.emailVerified },
            identity.avatarUrl,
            if (active) UserStatus.ACTIVE else UserStatus.PENDING,
            now().takeIf { consent },
        ).id
    }

    private fun allowed(identity: VerifiedIdentity): Boolean {
        val email = identity.email?.takeIf { identity.emailVerified }?.lowercase()
        val github = identity.login?.takeIf { identity.provider == GITHUB }?.let { "github:${it.lowercase()}" }
        return listOfNotNull(email, email?.let { "@" + it.substringAfter('@') }, github).any(allow::contains)
    }

    private fun vouches(orgId: UUID) = !(Memberships innerJoin Users innerJoin Organizations).select(Memberships.userId)
        .where {
            (Memberships.orgId eq orgId) and (Memberships.role eq OrgRole.OWNER.sql) and
                (Users.status eq UserStatus.ACTIVE.sql) and Organizations.suspendedAt.isNull()
        }
        .empty()

    private fun refuseSuspended(userId: UUID) {
        if (Users.select(Users.status).where { Users.id eq userId }.single()[Users.status] == UserStatus.SUSPENDED.sql) {
            throw LiftgateException(HttpStatusCode.Forbidden, "account_suspended", "this account is suspended")
        }
    }

    private fun remember(userId: UUID, identity: VerifiedIdentity, action: String) {
        Identities.upsert(Identities.provider, Identities.subject, onUpdateExclude = listOf(Identities.id, Identities.userId, Identities.createdAt)) {
            it[id] = UUID.randomUUID()
            it[Identities.userId] = userId
            it[provider] = identity.provider
            it[subject] = identity.subject
            it[email] = identity.email
            it[emailVerified] = identity.emailVerified
            it[lastUsedAt] = now()
        }
        audit(userId, action, identity.provider)
    }
}
