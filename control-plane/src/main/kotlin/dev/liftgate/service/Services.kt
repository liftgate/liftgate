package dev.liftgate.service

import dev.liftgate.db.Db
import dev.liftgate.db.Environments
import dev.liftgate.db.Memberships
import dev.liftgate.db.Organizations
import dev.liftgate.db.Projects
import dev.liftgate.db.Services as ServicesTable
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.org.Limits
import dev.liftgate.org.toOrganization
import dev.liftgate.project.toEnvironment
import dev.liftgate.project.toProject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.util.UUID

fun ResultRow.toService() = Service(
    this[ServicesTable.id],
    this[ServicesTable.environmentId],
    this[ServicesTable.slug],
    this[ServicesTable.name],
    this[ServicesTable.kind].toEnum(),
    this[ServicesTable.rootDir],
    this[ServicesTable.buildStrategy].toEnum(),
    this[ServicesTable.dockerfilePath],
    this[ServicesTable.port],
    this[ServicesTable.replicas],
    this[ServicesTable.cpuMillis],
    this[ServicesTable.memoryMb],
    this[ServicesTable.cronSchedule],
    this[ServicesTable.startCommand],
)

fun orgServiceIds(orgId: UUID) = (ServicesTable innerJoin Environments innerJoin Projects).select(ServicesTable.id).where { Projects.orgId eq orgId }

/**
 * @author Dean
 * @date 9/17/2026
 */
class Services(private val db: Db, private val limits: Limits = Limits()) {
    suspend fun create(environmentId: UUID, spec: ServiceSpec): Service = db.tx {
        limits.service(environmentId, spec)
        ServicesTable.insertReturning {
            it[id] = UUID.randomUUID()
            it[ServicesTable.environmentId] = environmentId
            it.set(spec)
        }.single().toService()
    }

    suspend fun scope(id: UUID): ServiceScope? = db.tx {
        (ServicesTable innerJoin Environments innerJoin Projects innerJoin Organizations).selectAll()
            .where { ServicesTable.id eq id }
            .singleOrNull()?.let { ServiceScope(it.toService(), it.toEnvironment(), it.toProject(), it.toOrganization()) }
    }

    suspend fun idsForOrg(orgId: UUID): List<UUID> = db.tx { orgServiceIds(orgId).map { it[ServicesTable.id] } }

    suspend fun tree(orgSlug: String, projectSlug: String, userId: UUID): ProjectTree? = db.tx {
        val project = (Projects innerJoin Organizations innerJoin Memberships).selectAll()
            .where { (Organizations.slug eq orgSlug) and (Projects.slug eq projectSlug) and (Memberships.userId eq userId) }
            .singleOrNull()?.toProject() ?: return@tx null
        ProjectTree(
            project,
            Environments.selectAll().where { Environments.projectId eq project.id }.orderBy(Environments.slug).map { it.toEnvironment() },
            (ServicesTable innerJoin Environments).selectAll().where { Environments.projectId eq project.id }.orderBy(ServicesTable.slug).map { it.toService() },
        )
    }

    suspend fun forEnvironment(environmentId: UUID): List<Service> = db.tx {
        ServicesTable.selectAll().where { ServicesTable.environmentId eq environmentId }.orderBy(ServicesTable.slug).map { it.toService() }
    }

    suspend fun update(id: UUID, spec: ServiceSpec): Service = db.tx {
        limits.resize(id, spec)
        ServicesTable.updateReturning(ServicesTable.columns, { ServicesTable.id eq id }) { it.set(spec) }.single().toService()
    }

    suspend fun delete(id: UUID) {
        db.tx {
            val namespace = (ServicesTable innerJoin Environments).select(Environments.namespace)
                .where { ServicesTable.id eq id }
                .singleOrNull()?.get(Environments.namespace) ?: return@tx
            ServicesTable.deleteWhere { ServicesTable.id eq id }
            enqueue(Subject.TEARDOWN_REQUESTED, buildJsonObject { put("namespace", namespace); put("serviceId", id.toString()) })
        }
    }

    private fun UpdateBuilder<*>.set(spec: ServiceSpec) {
        this[ServicesTable.slug] = spec.slug
        this[ServicesTable.name] = spec.name
        this[ServicesTable.kind] = spec.kind.sql
        this[ServicesTable.rootDir] = spec.rootDir
        this[ServicesTable.buildStrategy] = spec.buildStrategy.sql
        this[ServicesTable.dockerfilePath] = spec.dockerfilePath
        this[ServicesTable.port] = spec.port
        this[ServicesTable.replicas] = spec.replicas
        this[ServicesTable.cpuMillis] = spec.cpuMillis
        this[ServicesTable.memoryMb] = spec.memoryMb
        this[ServicesTable.cronSchedule] = spec.cronSchedule
        this[ServicesTable.startCommand] = spec.startCommand
    }
}
