package dev.liftgate.database

import dev.liftgate.db.Databases as DatabasesTable
import dev.liftgate.db.Db
import dev.liftgate.db.Environments
import dev.liftgate.db.Organizations
import dev.liftgate.db.Projects
import dev.liftgate.db.ServiceLinks
import dev.liftgate.db.Services
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.http.conflict
import dev.liftgate.http.invalid
import dev.liftgate.org.Limits
import dev.liftgate.org.toOrganization
import dev.liftgate.project.toEnvironment
import dev.liftgate.project.toProject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private val clusterServices = listOf("-rw", "-ro", "-r")

fun ResultRow.toDatabase() = Database(
    this[DatabasesTable.id],
    this[DatabasesTable.environmentId],
    this[DatabasesTable.slug],
    this[DatabasesTable.storageGb],
    this[DatabasesTable.cpuMillis],
    this[DatabasesTable.memoryMb],
    this[DatabasesTable.restoredFrom],
    this[DatabasesTable.restoreTarget]?.toInstant(),
    this[DatabasesTable.createdAt].toInstant(),
)

fun databaseClaiming(environmentId: UUID, serviceSlug: String): String? {
    val owners = clusterServices.filter { serviceSlug.endsWith(it) }.map { serviceSlug.removeSuffix(it) }
    return DatabasesTable.select(DatabasesTable.slug).where { (DatabasesTable.environmentId eq environmentId) and (DatabasesTable.slug inList owners) }.firstOrNull()?.get(DatabasesTable.slug)
}

/**
 * @author Dean
 * @date 9/30/2026
 */
class Databases(private val db: Db, private val limits: Limits = Limits()) {
    suspend fun create(environmentId: UUID, spec: DatabaseSpec, restoredFrom: UUID? = null, restoreTarget: Instant? = null): Database = db.tx {
        limits.database(environmentId, spec)
        Services.select(Services.slug).where { (Services.environmentId eq environmentId) and (Services.slug inList clusterServices.map { spec.slug + it }) }.firstOrNull()
            ?.let { invalid("the service ${it[Services.slug]} uses a name this database needs", "slug") }
        DatabasesTable.insertReturning {
            it[id] = UUID.randomUUID()
            it[DatabasesTable.environmentId] = environmentId
            it[slug] = spec.slug
            it[storageGb] = spec.storageGb
            it[cpuMillis] = spec.cpuMillis
            it[memoryMb] = spec.memoryMb
            it[DatabasesTable.restoredFrom] = restoredFrom
            it[DatabasesTable.restoreTarget] = restoreTarget?.atOffset(ZoneOffset.UTC)
        }.single().toDatabase().also { request(it.id) }
    }

    suspend fun forEnvironment(environmentId: UUID): List<Database> = db.tx {
        val links = (ServiceLinks innerJoin DatabasesTable).selectAll().where { DatabasesTable.environmentId eq environmentId }
            .groupBy({ it[ServiceLinks.databaseId] }) { ServiceLink(it[ServiceLinks.serviceId], it[ServiceLinks.envName]) }
        DatabasesTable.selectAll().where { DatabasesTable.environmentId eq environmentId }.orderBy(DatabasesTable.slug)
            .map { it.toDatabase().copy(links = links[it[DatabasesTable.id]].orEmpty()) }
    }

    suspend fun scope(id: UUID): DatabaseScope? = db.tx {
        (DatabasesTable innerJoin Environments innerJoin Projects innerJoin Organizations).selectAll().where { DatabasesTable.id eq id }
            .singleOrNull()?.let { DatabaseScope(it.toDatabase(), it.toEnvironment(), it.toProject(), it.toOrganization()) }
    }

    suspend fun all(): List<Pair<String, UUID>> = db.tx {
        (DatabasesTable innerJoin Environments).select(Environments.namespace, DatabasesTable.id).map { it[Environments.namespace] to it[DatabasesTable.id] }
    }

    suspend fun links(serviceId: UUID): Map<String, String> = db.tx {
        (ServiceLinks innerJoin DatabasesTable).select(ServiceLinks.envName, DatabasesTable.slug).where { ServiceLinks.serviceId eq serviceId }
            .associate { it[ServiceLinks.envName] to it[DatabasesTable.slug] }
    }

    suspend fun link(databaseId: UUID, link: ServiceLink) {
        db.tx {
            ServiceLinks.upsert {
                it[serviceId] = link.serviceId
                it[ServiceLinks.databaseId] = databaseId
                it[envName] = link.envName
            }
        }
    }

    suspend fun unlink(databaseId: UUID, serviceId: UUID) {
        db.tx { ServiceLinks.deleteWhere { (ServiceLinks.databaseId eq databaseId) and (ServiceLinks.serviceId eq serviceId) } }
    }

    suspend fun delete(id: UUID) {
        db.tx {
            (ServiceLinks innerJoin Services).select(Services.slug).where { ServiceLinks.databaseId eq id }.firstOrNull()
                ?.let { conflict("the service ${it[Services.slug]} is linked to this database; unlink it first") }
            request(id)
            DatabasesTable.deleteWhere { DatabasesTable.id eq id }
        }
    }

    private fun JdbcTransaction.request(id: UUID) {
        val namespace = (DatabasesTable innerJoin Environments).select(Environments.namespace).where { DatabasesTable.id eq id }.single()[Environments.namespace]
        enqueue(Subject.DATABASE_REQUESTED, buildJsonObject { put("namespace", namespace); put("databaseId", id.toString()) })
    }
}
