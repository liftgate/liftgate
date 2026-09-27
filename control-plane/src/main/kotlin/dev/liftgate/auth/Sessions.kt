package dev.liftgate.auth

import com.hazelcast.query.Predicates
import dev.liftgate.cache.Cache
import dev.liftgate.db.Db
import dev.liftgate.db.Sessions as SessionsTable
import dev.liftgate.db.now
import dev.liftgate.org.Orgs
import dev.liftgate.org.User
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.deleteReturning
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

val sessionLifetime = 30.days
private const val REVOKED = ""

/**
 * @author Dean
 * @date 9/17/2026
 */
class Sessions(private val db: Db, private val cache: Cache, private val orgs: Orgs) {
    suspend fun create(userId: UUID): String {
        val id = randomToken()
        val key = ApiTokens.hash(id)
        db.tx {
            SessionsTable.insert {
                it[SessionsTable.id] = key
                it[SessionsTable.userId] = userId
                it[expiresAt] = now().plus(sessionLifetime.toJavaDuration())
            }
        }
        cache.sessions[key] = userId.toString()
        return id
    }

    suspend fun resolve(id: String): User? {
        val key = ApiTokens.hash(id)
        val cached = cache.sessions[key]
        if (cached == REVOKED) return null
        val userId = cached?.let(UUID::fromString) ?: lookup(key) ?: return null
        return orgs.user(userId)
    }

    fun evict(userId: UUID) = cache.sessions.removeAll(Predicates.equal("this", userId.toString()))

    suspend fun delete(id: String) = revoke { SessionsTable.id eq ApiTokens.hash(id) }

    suspend fun deleteAll(userId: UUID) = revoke { SessionsTable.userId eq userId }

    private suspend fun revoke(where: () -> Op<Boolean>) = db.tx {
        SessionsTable.deleteReturning(listOf(SessionsTable.id), where).map { it[SessionsTable.id] }
    }.forEach { cache.sessions.set(it, REVOKED, 1, TimeUnit.MINUTES) }

    private suspend fun lookup(id: String): UUID? = db.tx {
        SessionsTable.select(SessionsTable.userId)
            .where { (SessionsTable.id eq id) and (SessionsTable.expiresAt greater now()) }
            .singleOrNull()?.get(SessionsTable.userId)
    }?.also { cache.sessions.putIfAbsent(id, it.toString()) }
}
