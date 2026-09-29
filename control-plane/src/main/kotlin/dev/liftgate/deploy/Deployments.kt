package dev.liftgate.deploy

import dev.liftgate.db.Builds as BuildsTable
import dev.liftgate.db.Db
import dev.liftgate.db.Deployments as DeploymentsTable
import dev.liftgate.db.Services as ServicesTable
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.events.requestedSince
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.conflict
import dev.liftgate.http.notFound
import dev.liftgate.org.Limits
import dev.liftgate.service.sealedEnv
import dev.liftgate.service.toService
import io.ktor.http.HttpStatusCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private const val REQUEUE_MINUTES = 5L
private const val TIMEOUT_MINUTES = 45L
private val unreleased = listOf(DeploymentStatus.PENDING, DeploymentStatus.RELEASING).map { it.sql }
val live = unreleased + DeploymentStatus.RUNNING.sql
private val outcomes = setOf(DeploymentStatus.RUNNING, DeploymentStatus.FAILED)

fun ResultRow.toDeployment() = Deployment(
    this[DeploymentsTable.id],
    this[DeploymentsTable.serviceId],
    this[DeploymentsTable.buildId],
    this[DeploymentsTable.status].toEnum(),
    this[DeploymentsTable.replicasReady],
    this[DeploymentsTable.error],
    this[DeploymentsTable.createdAt].toInstant(),
    this[DeploymentsTable.config],
    this[DeploymentsTable.env],
    this[DeploymentsTable.health]?.toEnum<DeploymentHealth>(),
)

fun JdbcTransaction.createDeployment(serviceId: UUID, buildId: UUID, snapshotOf: Deployment? = null): Deployment {
    val spec = ServicesTable.selectAll().where { ServicesTable.id eq serviceId }.forUpdate().single().toService().spec()
    val sealed = snapshotOf?.env ?: sealedEnv(serviceId)
    val deployment = DeploymentsTable.insertReturning {
        it[id] = UUID.randomUUID()
        it[DeploymentsTable.serviceId] = serviceId
        it[DeploymentsTable.buildId] = buildId
        it[status] = DeploymentStatus.PENDING.sql
        it[config] = snapshotOf?.config ?: spec
        it[env] = sealed
    }.single().toDeployment()
    requestRelease(deployment.id)
    return deployment
}

private fun JdbcTransaction.requestRelease(id: UUID) = enqueue(Subject.RELEASE_REQUESTED, buildJsonObject { put("deploymentId", id.toString()) })

private fun JdbcTransaction.updated(id: UUID, status: DeploymentStatus, changed: Boolean = true) =
    enqueue(Subject.DEPLOYMENT_UPDATED, buildJsonObject { put("deploymentId", id.toString()); put("status", status.sql); put("changed", changed) })

/**
 * @author Dean
 * @date 9/17/2026
 */
class Deployments(private val db: Db, private val metrics: MeterRegistry = SimpleMeterRegistry(), private val limits: Limits = Limits()) {
    suspend fun byId(id: UUID): Deployment? = db.tx { find(id) }

    suspend fun forService(serviceId: UUID, limit: Int = 50): List<Deployment> = db.tx {
        DeploymentsTable.selectAll().where { DeploymentsTable.serviceId eq serviceId }
            .orderBy(DeploymentsTable.createdAt, SortOrder.DESC).limit(limit).map { it.toDeployment() }
    }

    suspend fun current(serviceId: UUID): Deployment? = db.tx {
        running(serviceId).orderBy(DeploymentsTable.createdAt, SortOrder.DESC).limit(1).singleOrNull()?.toDeployment()
    }

    suspend fun rollback(deploymentId: UUID): Deployment = db.tx {
        val target = find(deploymentId) ?: notFound("deployment")
        if (target.status == DeploymentStatus.RUNNING) conflict("that deployment is already running")
        if (BuildsTable.select(BuildsTable.imagePruned).where { BuildsTable.id eq target.buildId }.forUpdate().single()[BuildsTable.imagePruned]) {
            throw LiftgateException(HttpStatusCode.Conflict, "image_pruned", "the image of that build has been pruned; deploy its commit again")
        }
        target.config?.let { limits.resize(target.serviceId, it) }
        createDeployment(target.serviceId, target.buildId, snapshotOf = target)
    }

    suspend fun redeploy(serviceId: UUID): Deployment = db.tx {
        ServicesTable.select(ServicesTable.id).where { ServicesTable.id eq serviceId }.forUpdate().toList()
        val current = DeploymentsTable.select(DeploymentsTable.buildId)
            .where { (DeploymentsTable.serviceId eq serviceId) and (DeploymentsTable.status inList live) }
            .orderBy(DeploymentsTable.createdAt, SortOrder.DESC).limit(1).singleOrNull() ?: conflict("nothing is running; deploy a build first")
        createDeployment(serviceId, current[DeploymentsTable.buildId])
    }

    suspend fun fallback(failedId: UUID): Deployment? = db.tx {
        val failed = find(failedId)?.takeUnless { replaced(it) } ?: return@tx null
        running(failed.serviceId).andWhere { DeploymentsTable.id neq failedId }.orderBy(DeploymentsTable.createdAt, SortOrder.DESC).limit(1).singleOrNull()?.toDeployment()?.takeIf { it.env != null }
    }

    suspend fun transition(id: UUID, to: DeploymentStatus, replicasReady: Int = 0, error: String? = null, unreleasedOnly: Boolean = false) {
        val createdAt = db.tx {
            val current = find(id, lock = true) ?: notFound("deployment")
            if (unreleasedOnly && current.status.sql !in unreleased || !current.status.allows(to)) conflict("deployment cannot move from ${current.status.sql} to ${to.sql}")
            if (to == DeploymentStatus.FAILED && current.status == DeploymentStatus.RUNNING && replaced(current)) conflict("a running deployment with a newer one cannot fail")
            if (current.status == to && (to == DeploymentStatus.FAILED || current.replicasReady == replicasReady && current.error == error)) return@tx null
            DeploymentsTable.update({ DeploymentsTable.id eq id }) {
                it[status] = to.sql
                it[DeploymentsTable.replicasReady] = replicasReady
                it[DeploymentsTable.error] = error
                if (to == DeploymentStatus.RUNNING) it[reachedRunning] = true
            }
            if (to == DeploymentStatus.RUNNING) supersedeOlder(current)
            updated(id, to, current.status != to)
            current.createdAt.takeIf { current.status.sql in unreleased && to in outcomes }
        } ?: return
        released(to, createdAt)
    }

    suspend fun reverted(id: UUID, to: UUID) {
        val note = "reverted to $to"
        db.tx {
            val current = find(id, lock = true) ?: notFound("deployment")
            if (current.error?.endsWith(note) == true) return@tx
            DeploymentsTable.update({ DeploymentsTable.id eq id }) { it[error] = listOfNotNull(current.error, note).joinToString("; ") }
            updated(id, current.status, changed = false)
        }
    }

    suspend fun observe(id: UUID, replicasReady: Int, health: DeploymentHealth) {
        db.tx {
            val service = DeploymentsTable.select(DeploymentsTable.serviceId).where { DeploymentsTable.id eq id }
            DeploymentsTable.update({ (DeploymentsTable.serviceId inSubQuery service) and (DeploymentsTable.status eq DeploymentStatus.RUNNING.sql) }) {
                it[DeploymentsTable.replicasReady] = replicasReady
                it[DeploymentsTable.health] = health.sql
            }
        }
    }

    suspend fun redrive() {
        db.tx {
            val now = now()
            val timedOut = DeploymentsTable.updateReturning(listOf(DeploymentsTable.id, DeploymentsTable.createdAt), { unreleasedBefore(now.minusMinutes(TIMEOUT_MINUTES)) }) {
                it[status] = DeploymentStatus.FAILED.sql
                it[error] = "timed out"
            }.toList().onEach { updated(it[DeploymentsTable.id], DeploymentStatus.FAILED) }
            val requested = requestedSince(Subject.RELEASE_REQUESTED, "deploymentId", now.minusMinutes(REQUEUE_MINUTES))
            DeploymentsTable.select(DeploymentsTable.id).where { unreleasedBefore(now.minusMinutes(REQUEUE_MINUTES)) }
                .map { it[DeploymentsTable.id] }
                .filter { it.toString() !in requested }
                .forEach { requestRelease(it) }
            timedOut.map { it[DeploymentsTable.createdAt].toInstant() }
        }.forEach { released(DeploymentStatus.FAILED, it) }
    }

    private fun released(status: DeploymentStatus, createdAt: Instant) =
        metrics.timer("liftgate.release.duration", "status", status.sql).record(Duration.between(createdAt, Instant.now()))

    private fun find(id: UUID, lock: Boolean = false) =
        DeploymentsTable.selectAll().where { DeploymentsTable.id eq id }.apply { if (lock) forUpdate() }.singleOrNull()?.toDeployment()

    private fun replaced(deployment: Deployment) = !DeploymentsTable.select(DeploymentsTable.id).where {
        (DeploymentsTable.serviceId eq deployment.serviceId) and (DeploymentsTable.createdAt greater deployment.createdAt.atOffset(ZoneOffset.UTC))
    }.empty()

    private fun unreleasedBefore(cutoff: OffsetDateTime) = (DeploymentsTable.status inList unreleased) and (DeploymentsTable.createdAt less cutoff)

    private fun running(serviceId: UUID) =
        DeploymentsTable.selectAll().where { (DeploymentsTable.serviceId eq serviceId) and (DeploymentsTable.status eq DeploymentStatus.RUNNING.sql) }

    private fun JdbcTransaction.supersedeOlder(deployment: Deployment) = DeploymentsTable.updateReturning(listOf(DeploymentsTable.id), {
        (DeploymentsTable.serviceId eq deployment.serviceId) and
            (DeploymentsTable.id neq deployment.id) and
            (DeploymentsTable.createdAt less deployment.createdAt.atOffset(ZoneOffset.UTC)) and
            (DeploymentsTable.status inList live)
    }) { it[status] = DeploymentStatus.SUPERSEDED.sql }.map { it[DeploymentsTable.id] }.forEach { updated(it, DeploymentStatus.SUPERSEDED) }
}
