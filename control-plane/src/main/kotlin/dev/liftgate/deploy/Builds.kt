package dev.liftgate.deploy

import dev.liftgate.db.Builds as BuildsTable
import dev.liftgate.db.Db
import dev.liftgate.db.Services
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.events.requestedSince
import dev.liftgate.http.notFound
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private const val REQUEUE_MINUTES = 5L
private const val STALLED_MINUTES = 2L
private const val TIMEOUT_MINUTES = 45L
val active = listOf(BuildStatus.QUEUED, BuildStatus.RUNNING).map { it.sql }

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
    this[BuildsTable.imagePruned],
)

/**
 * @author Dean
 * @date 9/17/2026
 */
class Builds(private val db: Db) {
    suspend fun request(serviceId: UUID, commitSha: String, commitMessage: String?, branch: String): Build = db.tx {
        Services.select(Services.id).where { Services.id eq serviceId }.forUpdate().toList()
        BuildsTable.updateReturning(listOf(BuildsTable.id), { (BuildsTable.serviceId eq serviceId) and (BuildsTable.branch eq branch) and (BuildsTable.status eq BuildStatus.QUEUED.sql) }) {
            it[status] = BuildStatus.CANCELLED.sql
            it[finishedAt] = now()
        }.toList().forEach { completed(it[BuildsTable.id], BuildStatus.CANCELLED) }
        val build = BuildsTable.insertReturning {
            it[id] = UUID.randomUUID()
            it[BuildsTable.serviceId] = serviceId
            it[BuildsTable.commitSha] = commitSha
            it[BuildsTable.commitMessage] = commitMessage
            it[BuildsTable.branch] = branch
            it[status] = BuildStatus.QUEUED.sql
        }.single().toBuild()
        requestBuild(build.id)
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

    suspend fun markSucceeded(id: UUID, imageRef: String): Deployment? = db.tx {
        val build = find(id) ?: notFound("build")
        Services.select(Services.id).where { Services.id eq build.serviceId }.forUpdate().toList()
        val finished = BuildsTable.update({ unfinished(id) }) {
            it[status] = BuildStatus.SUCCEEDED.sql
            it[BuildsTable.imageRef] = imageRef
            it[finishedAt] = now()
        } == 0
        if (finished) return@tx null
        completed(id, BuildStatus.SUCCEEDED)
        val overtaken = !BuildsTable.select(BuildsTable.id).where {
            (BuildsTable.serviceId eq build.serviceId) and (BuildsTable.status eq BuildStatus.SUCCEEDED.sql) and (BuildsTable.createdAt greater build.createdAt.atOffset(ZoneOffset.UTC))
        }.empty()
        if (overtaken) null else createDeployment(build.serviceId, id)
    }

    suspend fun markFailed(id: UUID, error: String) {
        db.tx { fail(error) { unfinished(id) } }
    }

    suspend fun timeOut(status: BuildStatus) {
        db.tx {
            val cutoff = now().minusMinutes(TIMEOUT_MINUTES)
            fail("timed out") { stale(cutoff, cutoff) and (BuildsTable.status eq status.sql) }
        }
    }

    suspend fun requeue(withJobs: Set<String>) {
        db.tx {
            val now = now()
            val requested = requestedSince(Subject.BUILD_REQUESTED, "buildId", now.minusMinutes(REQUEUE_MINUTES))
            BuildsTable.select(BuildsTable.id, BuildsTable.status).where { stale(now.minusMinutes(REQUEUE_MINUTES), now.minusMinutes(STALLED_MINUTES)) }.toList()
                .filter { it[BuildsTable.status] == BuildStatus.QUEUED.sql || it[BuildsTable.id].toString() !in withJobs }
                .map { it[BuildsTable.id] }
                .filter { it.toString() !in requested }
                .forEach { requestBuild(it) }
        }
    }

    private fun find(id: UUID) = BuildsTable.selectAll().where { BuildsTable.id eq id }.singleOrNull()?.toBuild()

    private fun unfinished(id: UUID) = (BuildsTable.id eq id) and (BuildsTable.status inList active)

    private fun stale(queuedBefore: OffsetDateTime, startedBefore: OffsetDateTime): Op<Boolean> =
        ((BuildsTable.status eq BuildStatus.QUEUED.sql) and (BuildsTable.createdAt less queuedBefore)) or
            ((BuildsTable.status eq BuildStatus.RUNNING.sql) and (BuildsTable.startedAt less startedBefore))

    private fun JdbcTransaction.fail(error: String, where: () -> Op<Boolean>) = BuildsTable.updateReturning(listOf(BuildsTable.id), where) {
        it[status] = BuildStatus.FAILED.sql
        it[BuildsTable.error] = error
        it[finishedAt] = now()
    }.toList().forEach { completed(it[BuildsTable.id], BuildStatus.FAILED) }

    private fun JdbcTransaction.requestBuild(id: UUID) = enqueue(Subject.BUILD_REQUESTED, buildJsonObject { put("buildId", id.toString()) })

    private fun JdbcTransaction.completed(id: UUID, status: BuildStatus) =
        enqueue(Subject.BUILD_COMPLETED, buildJsonObject { put("buildId", id.toString()); put("status", status.sql) })
}
