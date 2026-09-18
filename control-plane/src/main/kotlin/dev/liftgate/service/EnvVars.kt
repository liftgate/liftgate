package dev.liftgate.service

import dev.liftgate.db.Db
import dev.liftgate.db.EnvVars as EnvVarsTable
import dev.liftgate.http.invalid
import dev.liftgate.secret.SecretBox
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
class EnvVars(private val db: Db, private val secrets: SecretBox) {
    suspend fun list(serviceId: UUID, reveal: Boolean): List<EnvVar> = db.tx {
        EnvVarsTable.selectAll().where { EnvVarsTable.serviceId eq serviceId }.orderBy(EnvVarsTable.name).map {
            val secret = it[EnvVarsTable.isSecret]
            EnvVar(it[EnvVarsTable.name], if (secret && !reveal) null else secrets.open(it[EnvVarsTable.valueEncrypted]), secret)
        }
    }

    suspend fun replace(serviceId: UUID, vars: List<EnvVar>) {
        db.tx {
            val existingSecrets = EnvVarsTable.select(EnvVarsTable.name, EnvVarsTable.valueEncrypted)
                .where { (EnvVarsTable.serviceId eq serviceId) and (EnvVarsTable.isSecret eq true) }
                .associate { it[EnvVarsTable.name] to it[EnvVarsTable.valueEncrypted] }
            val sealed = vars.map {
                it to (it.value?.let(secrets::seal) ?: existingSecrets[it.name]?.takeIf { _ -> it.secret } ?: invalid("value is required for ${it.name}"))
            }
            EnvVarsTable.deleteWhere { EnvVarsTable.serviceId eq serviceId }
            EnvVarsTable.batchInsert(sealed) { (envVar, value) ->
                this[EnvVarsTable.id] = UUID.randomUUID()
                this[EnvVarsTable.serviceId] = serviceId
                this[EnvVarsTable.name] = envVar.name
                this[EnvVarsTable.valueEncrypted] = value
                this[EnvVarsTable.isSecret] = envVar.secret
            }
        }
    }
}
