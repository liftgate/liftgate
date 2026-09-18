package dev.liftgate.auth

import dev.liftgate.db.ApiTokens as ApiTokensTable
import dev.liftgate.db.Db
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
class ApiTokens(private val db: Db) {
    suspend fun create(orgId: UUID, name: String, createdBy: UUID): String {
        val token = "lg_" + randomToken()
        db.tx {
            ApiTokensTable.insert {
                it[id] = UUID.randomUUID()
                it[ApiTokensTable.orgId] = orgId
                it[ApiTokensTable.name] = name
                it[tokenHash] = hash(token)
                it[ApiTokensTable.createdBy] = createdBy
            }
        }
        return token
    }

    suspend fun resolve(token: String): Pair<UUID, UUID>? = db.tx {
        ApiTokensTable.select(ApiTokensTable.orgId, ApiTokensTable.createdBy)
            .where { ApiTokensTable.tokenHash eq hash(token) }
            .singleOrNull()?.let { it[ApiTokensTable.orgId] to it[ApiTokensTable.createdBy] }
    }

    companion object {
        fun hash(token: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    }
}
