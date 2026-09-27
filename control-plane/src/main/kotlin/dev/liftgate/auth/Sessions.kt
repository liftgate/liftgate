package dev.liftgate.auth

import com.hazelcast.query.Predicates
import dev.liftgate.cache.Cache
import dev.liftgate.db.Db
import dev.liftgate.db.Sessions as SessionsTable
import dev.liftgate.db.now
import dev.liftgate.org.Orgs
import dev.liftgate.org.User
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.deleteReturning
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

val sessionLifetime = 30.days

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
        val userId = cache.sessions[key]?.let(UUID::fromString) ?: lookup(key) ?: return null
        return orgs.user(userId)
    }

    fun evict(userId: UUID) = cache.sessions.removeAll(Predicates.equal("this", userId.toString()))

    suspend fun delete(id: String) {
        val key = ApiTokens.hash(id)
        db.tx { SessionsTable.deleteWhere { SessionsTable.id eq key } }
        cache.sessions.remove(key)
    }

    suspend fun deleteAll(userId: UUID) = db.tx {
        SessionsTable.deleteReturning(listOf(SessionsTable.id)) { SessionsTable.userId eq userId }.map { it[SessionsTable.id] }
    }.forEach(cache.sessions::delete)

    private suspend fun lookup(id: String): UUID? = db.tx {
        SessionsTable.select(SessionsTable.userId)
            .where { (SessionsTable.id eq id) and (SessionsTable.expiresAt greater now()) }
            .singleOrNull()?.get(SessionsTable.userId)
    }?.also { cache.sessions[id] = it.toString() }
}
