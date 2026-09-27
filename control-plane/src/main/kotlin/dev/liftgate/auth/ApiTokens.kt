package dev.liftgate.auth

import dev.liftgate.cache.Cache
import dev.liftgate.db.ApiTokens as ApiTokensTable
import dev.liftgate.db.Db
import dev.liftgate.db.Users
import dev.liftgate.db.now
import dev.liftgate.http.notFound
import dev.liftgate.org.toUser
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteReturning
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

private const val TOKEN = "api_token"

/**
 * @author Dean
 * @date 9/17/2026
 */
class ApiTokens(private val db: Db, private val cache: Cache) {
    suspend fun create(orgId: UUID, name: String, createdBy: UUID, expiresInDays: Int?): String {
        val token = "lg_" + randomToken()
        val id = UUID.randomUUID()
        db.tx {
            ApiTokensTable.insert {
                it[ApiTokensTable.id] = id
                it[ApiTokensTable.orgId] = orgId
                it[ApiTokensTable.name] = name
                it[tokenHash] = hash(token)
                it[ApiTokensTable.createdBy] = createdBy
                it[expiresAt] = expiresInDays?.let { days -> now().plusDays(days.toLong()) }
            }
            audit(createdBy, "token.create", TOKEN, id, "name" to name, orgId)
        }
        return token
    }

    suspend fun list(orgId: UUID): List<ApiToken> = db.tx {
        (ApiTokensTable innerJoin Users).selectAll().where { ApiTokensTable.orgId eq orgId }.orderBy(ApiTokensTable.createdAt, SortOrder.DESC).map {
            ApiToken(
                it[ApiTokensTable.id],
                it[ApiTokensTable.name],
                it.toUser(),
                it[ApiTokensTable.createdAt].toInstant(),
                it[ApiTokensTable.lastUsedAt]?.toInstant(),
                it[ApiTokensTable.expiresAt]?.toInstant(),
            )
        }
    }

    suspend fun delete(orgId: UUID, id: UUID, actor: UUID) = db.tx {
        val name = ApiTokensTable.deleteReturning(listOf(ApiTokensTable.name)) { (ApiTokensTable.id eq id) and (ApiTokensTable.orgId eq orgId) }
            .singleOrNull()?.get(ApiTokensTable.name) ?: notFound("token")
        audit(actor, "token.revoke", TOKEN, id, "name" to name, orgId)
    }

    suspend fun resolve(token: String): Pair<UUID, UUID>? = db.tx {
        ApiTokensTable.select(ApiTokensTable.id, ApiTokensTable.orgId, ApiTokensTable.createdBy)
            .where { (ApiTokensTable.tokenHash eq hash(token)) and (ApiTokensTable.expiresAt.isNull() or (ApiTokensTable.expiresAt greater now())) }
            .singleOrNull()?.let { row ->
                val id = row[ApiTokensTable.id]
                if (cache.allow("token-used:$id:${System.currentTimeMillis() / 60_000}", 1)) ApiTokensTable.update({ ApiTokensTable.id eq id }) { it[lastUsedAt] = now() }
                row[ApiTokensTable.orgId] to row[ApiTokensTable.createdBy]
            }
    }

    companion object {
        fun hash(token: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    }
}
