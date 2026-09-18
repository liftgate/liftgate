package dev.liftgate.deploy

import dev.liftgate.db.Db
import dev.liftgate.db.Deployments as DeploymentsTable
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.http.conflict
import dev.liftgate.http.notFound
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.ZoneOffset
import java.util.UUID

private val live = listOf(DeploymentStatus.PENDING, DeploymentStatus.RELEASING, DeploymentStatus.RUNNING).map { it.sql }

fun ResultRow.toDeployment() = Deployment(
    this[DeploymentsTable.id],
    this[DeploymentsTable.serviceId],
    this[DeploymentsTable.buildId],
    this[DeploymentsTable.status].toEnum(),
    this[DeploymentsTable.replicasReady],
    this[DeploymentsTable.error],
    this[DeploymentsTable.createdAt].toInstant(),
)

fun JdbcTransaction.createDeployment(serviceId: UUID, buildId: UUID): Deployment {
    val deployment = DeploymentsTable.insertReturning {
        it[id] = UUID.randomUUID()
        it[DeploymentsTable.serviceId] = serviceId
        it[DeploymentsTable.buildId] = buildId
        it[status] = DeploymentStatus.PENDING.sql
    }.single().toDeployment()
    enqueue(Subject.RELEASE_REQUESTED, buildJsonObject { put("deploymentId", deployment.id.toString()) })
    return deployment
}

/**
 * @author Dean
 * @date 9/17/2026
 */
class Deployments(private val db: Db) {
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
        if (running(target.serviceId).any { it[DeploymentsTable.buildId] == target.buildId }) conflict("that build is already running")
        DeploymentsTable.update({ (DeploymentsTable.serviceId eq target.serviceId) and (DeploymentsTable.status eq DeploymentStatus.RUNNING.sql) }) {
            it[status] = DeploymentStatus.ROLLED_BACK.sql
        }
        createDeployment(target.serviceId, target.buildId)
    }

    suspend fun transition(id: UUID, to: DeploymentStatus, replicasReady: Int = 0, error: String? = null) {
        db.tx {
            val current = find(id) ?: notFound("deployment")
            if (!current.status.allows(to)) conflict("deployment cannot move from ${current.status.sql} to ${to.sql}")
            if (current.status == to && current.replicasReady == replicasReady && current.error == error) return@tx
            DeploymentsTable.update({ DeploymentsTable.id eq id }) {
                it[status] = to.sql
                it[DeploymentsTable.replicasReady] = replicasReady
                it[DeploymentsTable.error] = error
            }
            if (to == DeploymentStatus.RUNNING) supersedeOlder(current)
            enqueue(Subject.DEPLOYMENT_UPDATED, buildJsonObject { put("deploymentId", id.toString()); put("status", to.sql) })
        }
    }

    private fun find(id: UUID) = DeploymentsTable.selectAll().where { DeploymentsTable.id eq id }.singleOrNull()?.toDeployment()

    private fun running(serviceId: UUID) =
        DeploymentsTable.selectAll().where { (DeploymentsTable.serviceId eq serviceId) and (DeploymentsTable.status eq DeploymentStatus.RUNNING.sql) }

    private fun supersedeOlder(deployment: Deployment) = DeploymentsTable.update({
        (DeploymentsTable.serviceId eq deployment.serviceId) and
            (DeploymentsTable.id neq deployment.id) and
            (DeploymentsTable.createdAt less deployment.createdAt.atOffset(ZoneOffset.UTC)) and
            (DeploymentsTable.status inList live)
    }) { it[status] = DeploymentStatus.SUPERSEDED.sql }
}
