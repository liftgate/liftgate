package dev.liftgate.auth

import dev.liftgate.cache.Cache
import dev.liftgate.db.Db
import dev.liftgate.db.Sessions as SessionsTable
import dev.liftgate.db.now
import dev.liftgate.org.Orgs
import dev.liftgate.org.User
import dev.liftgate.secret.SecretBox
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
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
class Sessions(private val db: Db, private val cache: Cache, private val orgs: Orgs, private val secrets: SecretBox) {
    suspend fun create(userId: UUID, githubToken: String): String {
        val id = randomToken()
        db.tx {
            SessionsTable.insert {
                it[SessionsTable.id] = id
                it[SessionsTable.userId] = userId
                it[SessionsTable.githubToken] = secrets.seal(githubToken)
                it[expiresAt] = now().plus(sessionLifetime.toJavaDuration())
            }
        }
        cache.sessions[id] = userId.toString()
        return id
    }

    suspend fun resolve(id: String): User? {
        val userId = cache.sessions[id]?.let(UUID::fromString) ?: lookup(id) ?: return null
        return orgs.user(userId)
    }

    suspend fun githubToken(id: String): String? = db.tx {
        SessionsTable.select(SessionsTable.githubToken)
            .where { (SessionsTable.id eq id) and (SessionsTable.expiresAt greater now()) }
            .singleOrNull()?.let { secrets.open(it[SessionsTable.githubToken]) }
    }

    suspend fun delete(id: String) {
        db.tx { SessionsTable.deleteWhere { SessionsTable.id eq id } }
        cache.sessions.remove(id)
    }

    private suspend fun lookup(id: String): UUID? = db.tx {
        SessionsTable.select(SessionsTable.userId)
            .where { (SessionsTable.id eq id) and (SessionsTable.expiresAt greater now()) }
            .singleOrNull()?.get(SessionsTable.userId)
    }?.also { cache.sessions[id] = it.toString() }
}
