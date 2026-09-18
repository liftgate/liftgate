package dev.liftgate.deploy

import dev.liftgate.db.Builds as BuildsTable
import dev.liftgate.db.Db
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.http.notFound
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

fun ResultRow.toBuild() = Build(
    this[BuildsTable.id],
    this[BuildsTable.serviceId],
    this[BuildsTable.commitSha],
    this[BuildsTable.commitMessage],
    this[BuildsTable.branch],
    this[BuildsTable.status].toEnum(),
    this[BuildsTable.imageRef],
    this[BuildsTable.error],
    this[BuildsTable.startedAt]?.toInstant(),
    this[BuildsTable.finishedAt]?.toInstant(),
    this[BuildsTable.createdAt].toInstant(),
)

/**
 * @author Dean
 * @date 9/17/2026
 */
class Builds(private val db: Db) {
    suspend fun request(serviceId: UUID, commitSha: String, commitMessage: String?, branch: String): Build = db.tx {
        val build = BuildsTable.insertReturning {
            it[id] = UUID.randomUUID()
            it[BuildsTable.serviceId] = serviceId
            it[BuildsTable.commitSha] = commitSha
            it[BuildsTable.commitMessage] = commitMessage
            it[BuildsTable.branch] = branch
            it[status] = BuildStatus.QUEUED.sql
        }.single().toBuild()
        enqueue(Subject.BUILD_REQUESTED, buildJsonObject { put("buildId", build.id.toString()) })
        build
    }

    suspend fun byId(id: UUID): Build? = db.tx { find(id) }

    suspend fun forService(serviceId: UUID, limit: Int = 50): List<Build> = db.tx {
        BuildsTable.selectAll().where { BuildsTable.serviceId eq serviceId }.orderBy(BuildsTable.createdAt, SortOrder.DESC).limit(limit).map { it.toBuild() }
    }

    suspend fun markRunning(id: UUID) {
        db.tx {
            BuildsTable.update({ BuildsTable.id eq id }) {
                it[status] = BuildStatus.RUNNING.sql
                it[startedAt] = now()
            }
        }
    }

    suspend fun markSucceeded(id: UUID, imageRef: String): Deployment = db.tx {
        val build = find(id) ?: notFound("build")
        BuildsTable.update({ BuildsTable.id eq id }) {
            it[status] = BuildStatus.SUCCEEDED.sql
            it[BuildsTable.imageRef] = imageRef
            it[finishedAt] = now()
        }
        completed(id, BuildStatus.SUCCEEDED)
        createDeployment(build.serviceId, id)
    }

    suspend fun markFailed(id: UUID, error: String) {
        db.tx {
            BuildsTable.update({ BuildsTable.id eq id }) {
                it[status] = BuildStatus.FAILED.sql
                it[BuildsTable.error] = error
                it[finishedAt] = now()
            }
            completed(id, BuildStatus.FAILED)
        }
    }

    private fun find(id: UUID) = BuildsTable.selectAll().where { BuildsTable.id eq id }.singleOrNull()?.toBuild()

    private fun JdbcTransaction.completed(id: UUID, status: BuildStatus) =
        enqueue(Subject.BUILD_COMPLETED, buildJsonObject { put("buildId", id.toString()); put("status", status.sql) })
}
