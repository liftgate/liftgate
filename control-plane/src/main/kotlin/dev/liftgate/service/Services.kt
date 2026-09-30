package dev.liftgate.service

import dev.liftgate.build.orphanRepositories
import dev.liftgate.db.Builds
import dev.liftgate.db.Db
import dev.liftgate.db.Deployments
import dev.liftgate.db.Domains
import dev.liftgate.db.Environments
import dev.liftgate.db.Memberships
import dev.liftgate.db.Organizations
import dev.liftgate.db.Projects
import dev.liftgate.db.Services as ServicesTable
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.domain.DomainKind
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.org.Limits
import dev.liftgate.org.toOrganization
import dev.liftgate.org.withRole
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.toEnvironment
import dev.liftgate.project.toProject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.util.UUID

fun ResultRow.toService(namespace: String? = getOrNull(Environments.namespace)) = Service(
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
    this[ServicesTable.healthCheckPath],
    this[ServicesTable.watchPaths],
).run { copy(internalHost = namespace?.takeIf { listens }?.let { "$slug.$it.svc.cluster.local" }) }

fun orgServiceIds(orgId: UUID) = (ServicesTable innerJoin Environments innerJoin Projects).select(ServicesTable.id).where { Projects.orgId eq orgId }

/**
 * @author Dean
 * @date 9/17/2026
 */
class Services(private val db: Db, private val limits: Limits = Limits()) {
    suspend fun create(environmentId: UUID, spec: ServiceSpec): Service = db.tx { insert(environmentId, spec) }

    fun insert(environmentId: UUID, spec: ServiceSpec): Service {
        limits.service(environmentId, spec)
        return ServicesTable.insertReturning {
            it[id] = UUID.randomUUID()
            it[ServicesTable.environmentId] = environmentId
            it.set(spec)
        }.single().toService(namespace(environmentId))
    }

    suspend fun scope(id: UUID, userId: UUID? = null): ServiceScope? = db.tx {
        (ServicesTable innerJoin Environments innerJoin Projects innerJoin Organizations).withRole(userId).selectAll()
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
            (ServicesTable innerJoin Environments).selectAll().where { Environments.projectId eq project.id }.orderBy(ServicesTable.slug).map { it.toService() }.let(::status),
        )
    }

    suspend fun forEnvironment(environmentId: UUID): List<Service> = db.tx {
        (ServicesTable innerJoin Environments).selectAll().where { ServicesTable.environmentId eq environmentId }.orderBy(ServicesTable.slug).map { it.toService() }
    }

    suspend fun withStatus(services: List<Service>): List<Service> = db.tx { status(services) }

    suspend fun production(scope: ServiceScope): ServiceScope? = if (scope.environment.kind == EnvironmentKind.PRODUCTION) null else db.tx {
        (ServicesTable innerJoin Environments).selectAll()
            .where { (Environments.projectId eq scope.project.id) and (Environments.kind eq EnvironmentKind.PRODUCTION.sql) and (ServicesTable.slug eq scope.service.slug) }
            .orderBy(Environments.createdAt)
            .firstOrNull()?.let { scope.copy(service = it.toService(), environment = it.toEnvironment()) }
    }

    suspend fun update(id: UUID, spec: ServiceSpec): Service = db.tx {
        limits.resize(id, spec)
        ServicesTable.updateReturning(ServicesTable.columns, { ServicesTable.id eq id }) { it.set(spec) }.single().let { it.toService(namespace(it[ServicesTable.environmentId])) }
    }

    suspend fun delete(id: UUID) {
        db.tx {
            val namespace = (ServicesTable innerJoin Environments).select(Environments.namespace)
                .where { ServicesTable.id eq id }
                .singleOrNull()?.get(Environments.namespace) ?: return@tx
            orphanRepositories(ServicesTable.id eq id)
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
        this[ServicesTable.healthCheckPath] = spec.healthCheckPath
        this[ServicesTable.watchPaths] = spec.watchPaths
    }

    private fun status(services: List<Service>): List<Service> {
        if (services.isEmpty()) return services
        val ids = services.map { it.id }
        val hosts = Domains.select(Domains.serviceId, Domains.hostname)
            .where { (Domains.serviceId inList ids) and Domains.verifiedAt.isNotNull() }
            .withDistinctOn(Domains.serviceId to SortOrder.ASC)
            .orderBy((Domains.kind eq DomainKind.PLATFORM.sql) to SortOrder.DESC, Domains.verifiedAt to SortOrder.ASC)
            .associate { it[Domains.serviceId] to it[Domains.hostname] }
        val current = (Deployments innerJoin Builds).select(Deployments.serviceId, Deployments.id, Deployments.status, Deployments.replicasReady, Deployments.createdAt, Builds.commitSha)
            .where { Deployments.serviceId inList ids }
            .withDistinctOn(Deployments.serviceId to SortOrder.ASC)
            .orderBy((Deployments.status eq DeploymentStatus.RUNNING.sql) to SortOrder.DESC, Deployments.createdAt to SortOrder.DESC)
            .associate {
                it[Deployments.serviceId] to
                    Service.Current(it[Deployments.id], it[Deployments.status].toEnum(), it[Deployments.replicasReady], it[Builds.commitSha], it[Deployments.createdAt].toInstant())
            }
        return services.map { service -> service.copy(url = hosts[service.id]?.takeIf { service.kind.servesHttp }?.let { "https://$it" }, current = current[service.id]) }
    }

    private fun namespace(environmentId: UUID) = Environments.select(Environments.namespace).where { Environments.id eq environmentId }.single()[Environments.namespace]
}
