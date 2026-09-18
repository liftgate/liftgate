@file:UseSerializers(InstantSerializer::class)

package dev.liftgate.auth

import dev.liftgate.db.Db
import dev.liftgate.db.GitConnections as GitConnectionsTable
import dev.liftgate.db.now
import dev.liftgate.http.InstantSerializer
import dev.liftgate.secret.SecretBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.UUID

/**
 * @author Dean
 * @date 9/18/2026
 */
@Serializable
data class GitConnection(val provider: String, val accountLogin: String, val connectedAt: Instant)

/**
 * @author Dean
 * @date 9/18/2026
 */
class GitConnections(private val db: Db, private val secrets: SecretBox, private val oauth: OAuth) {
    suspend fun store(userId: UUID, accountLogin: String, tokens: OAuthTokens) {
        db.tx {
            GitConnectionsTable.upsert(onUpdateExclude = listOf(GitConnectionsTable.createdAt)) {
                it[this.userId] = userId
                it[provider] = GITHUB
                it[this.accountLogin] = accountLogin
                it[accessToken] = secrets.seal(tokens.accessToken)
                it[refreshToken] = tokens.refreshToken?.let(secrets::seal)
                it[expiresAt] = tokens.expiresAt
            }
        }
    }

    suspend fun githubToken(userId: UUID): String? {
        val row = db.tx { GitConnectionsTable.selectAll().where { (GitConnectionsTable.userId eq userId) and (GitConnectionsTable.provider eq GITHUB) }.singleOrNull() }
            ?: return null
        val expiresAt = row[GitConnectionsTable.expiresAt]
        return if (expiresAt == null || expiresAt.isAfter(now().plusMinutes(1))) secrets.open(row[GitConnectionsTable.accessToken]) else refresh(userId, row)
    }

    suspend fun list(userId: UUID): List<GitConnection> = db.tx {
        GitConnectionsTable.selectAll().where { GitConnectionsTable.userId eq userId }.map {
            GitConnection(it[GitConnectionsTable.provider], it[GitConnectionsTable.accountLogin], it[GitConnectionsTable.createdAt].toInstant())
        }
    }

    suspend fun delete(userId: UUID, provider: String) = db.tx {
        GitConnectionsTable.deleteWhere { (GitConnectionsTable.userId eq userId) and (GitConnectionsTable.provider eq provider) }
    }

    private suspend fun refresh(userId: UUID, row: ResultRow): String? = row[GitConnectionsTable.refreshToken]
        ?.let { oauth.refresh(oauth.provider(GITHUB), secrets.open(it)) }
        ?.also { store(userId, row[GitConnectionsTable.accountLogin], it) }
        ?.accessToken
}
