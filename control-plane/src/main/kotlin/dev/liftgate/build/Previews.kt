package dev.liftgate.build

import dev.liftgate.App
import dev.liftgate.db.EnvVars as EnvVarsTable
import dev.liftgate.db.Environments
import dev.liftgate.db.PullRequests
import dev.liftgate.db.Services as ServicesTable
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.claimPlatformDomain
import dev.liftgate.http.conflict
import dev.liftgate.project.Environment
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.Project
import dev.liftgate.project.PullRequest
import dev.liftgate.project.deleteEnvironments
import dev.liftgate.project.toEnvironment
import dev.liftgate.service.toService
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import org.slf4j.LoggerFactory
import java.util.UUID

private const val IDLE_DAYS = 14L
private const val HEADING = "**Liftgate preview**"

fun ResultRow.toPullRequest() = PullRequest(
    this[PullRequests.number],
    this[PullRequests.title],
    this[PullRequests.headRef],
    this[PullRequests.headSha],
    this[PullRequests.fork],
    this[PullRequests.approvedSha],
    this[PullRequests.error],
    this[PullRequests.updatedAt].toInstant(),
    this[PullRequests.commentId],
)

private fun pullRequestKey(projectId: UUID, number: Int) = (PullRequests.projectId eq projectId) and (PullRequests.number eq number)

private fun previewKey(projectId: UUID, number: Int) = (Environments.projectId eq projectId) and (Environments.pullRequest eq number)

fun JdbcTransaction.removePreview(projectId: UUID, number: Int) {
    deleteEnvironments { previewKey(projectId, number) }
    PullRequests.deleteWhere { pullRequestKey(projectId, number) }
}

fun JdbcTransaction.removeIdlePreviews() = PullRequests.select(PullRequests.projectId, PullRequests.number)
    .where { PullRequests.updatedAt less now().minusDays(IDLE_DAYS) }
    .toList()
    .forEach { removePreview(it[PullRequests.projectId], it[PullRequests.number]) }

/**
 * @author Dean
 * @date 9/30/2026
 */
class Previews(private val app: App) {
    private val log = LoggerFactory.getLogger(Previews::class.java)

    suspend fun status(project: Project): Status {
        val installation = try {
            app.github?.installationSettings(project.installationId)
        } catch (e: ResponseException) {
            null
        }
        val missing = installation?.run {
            listOfNotNull(
                "Subscribe to events: Pull request".takeUnless { "pull_request" in events },
                "Pull requests: Read-only".takeUnless { "pull_requests" in permissions },
                "Issues: Read and write".takeUnless { permissions["issues"] == "write" },
            )
        }
        return Status(missing, app.db.tx { PullRequests.selectAll().where { PullRequests.projectId eq project.id }.orderBy(PullRequests.number, SortOrder.DESC).map { it.toPullRequest() } })
    }

    suspend fun open(project: Project, request: PullRequest) {
        val pull = app.db.tx {
            PullRequests.upsertReturning {
                it[projectId] = project.id
                it[number] = request.number
                it[title] = request.title
                it[headRef] = request.headRef
                it[headSha] = request.headSha
                it[fork] = request.fork
                it[error] = null
                it[updatedAt] = now()
            }.single().toPullRequest()
        }
        if (pull.trusted) deploy(project, pull)
    }

    suspend fun approve(project: Project, number: Int, sha: String) {
        if (!project.previewsEnabled) conflict("previews are turned off for ${project.slug}")
        val pull = app.db.tx {
            PullRequests.updateReturning(PullRequests.columns, { pullRequestKey(project.id, number) and (PullRequests.headSha eq sha) }) {
                it[approvedSha] = sha
                it[error] = null
                it[updatedAt] = now()
            }.singleOrNull()?.toPullRequest()
        } ?: conflict("pull request #$number is no longer at ${sha.take(7)}; review its latest commit")
        deploy(project, pull)
    }

    suspend fun close(project: Project, number: Int) {
        val pull = app.db.tx { find(project.id, number).also { removePreview(project.id, number) } } ?: return
        if (pull.commentId != null) quietly { publish(project, pull, "$HEADING\n\nThe preview was removed when the pull request closed.") }
    }

    suspend fun comment(serviceId: UUID) {
        val scope = app.services.scope(serviceId) ?: return
        val number = scope.environment.pullRequest ?: return
        val pull = app.db.tx { find(scope.project.id, number) }?.takeIf { it.commentId != null } ?: return
        publish(scope.project, pull, render(pull, scope.environment))
    }

    private suspend fun deploy(project: Project, pull: PullRequest) {
        val environment = try {
            app.db.tx { Environments.selectAll().where { previewKey(project.id, pull.number) }.singleOrNull()?.toEnvironment() ?: clone(project, pull) }
        } catch (e: LiftgateException) {
            app.db.tx { PullRequests.update({ pullRequestKey(project.id, pull.number) }) { it[error] = e.message } }
            quietly { publish(project, pull, "$HEADING\n\nNo preview was deployed: ${e.message}") }
            throw e
        }
        app.services.forEnvironment(environment.id).forEach { service ->
            app.services.scope(service.id)?.let { app.claimPlatformDomain(it) }
            app.builds.request(service.id, pull.headSha, null, pull.headRef)
        }
        quietly { publish(project, pull, render(pull, environment)) }
    }

    private fun clone(project: Project, pull: PullRequest): Environment {
        val base = project.previewBaseEnvironmentId ?: Environments.select(Environments.id)
            .where { (Environments.projectId eq project.id) and (Environments.kind eq EnvironmentKind.PRODUCTION.sql) and Environments.pullRequest.isNull() }
            .orderBy(Environments.createdAt)
            .firstOrNull()?.get(Environments.id) ?: conflict("${project.slug} has no production environment to copy")
        val environment = app.projects.insertPreview(project.id, pull.number, pull.headRef)
        ServicesTable.selectAll().where { ServicesTable.environmentId eq base }.map { it.toService() }.forEach { service ->
            val copy = app.services.insert(environment.id, service.spec())
            EnvVarsTable.batchInsert(EnvVarsTable.selectAll().where { EnvVarsTable.serviceId eq service.id }.toList()) { row ->
                this[EnvVarsTable.id] = UUID.randomUUID()
                this[EnvVarsTable.serviceId] = copy.id
                this[EnvVarsTable.name] = row[EnvVarsTable.name]
                this[EnvVarsTable.valueEncrypted] = row[EnvVarsTable.valueEncrypted]
                this[EnvVarsTable.isSecret] = row[EnvVarsTable.isSecret]
            }
        }
        return environment
    }

    private suspend fun render(pull: PullRequest, environment: Environment) = buildString {
        append("$HEADING of `${pull.headSha.take(7)}`\n\n| Service | URL | Deployed |\n| --- | --- | --- |\n")
        app.services.withStatus(app.services.forEnvironment(environment.id)).forEach { service ->
            append("| ${service.slug} | ${service.url.orEmpty()} | ${service.current?.let { "${it.status.sql} at `${it.commitSha.take(7)}`" } ?: "not yet"} |\n")
        }
    }

    private suspend fun publish(project: Project, pull: PullRequest, body: String) {
        val github = app.github ?: return
        try {
            val id = github.asImporter(project, "issues" to "write") { github.comment(it, project.repoFullName, pull.number, pull.commentId, body) } ?: return
            if (pull.commentId == null) app.db.tx { PullRequests.update({ pullRequestKey(project.id, pull.number) }) { it[commentId] = id } }
        } catch (e: ClientRequestException) {
            if (e.rateLimited) throw e
            log.warn("GitHub refused the preview comment on {}#{}: {}", project.repoFullName, pull.number, e.response.status)
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) = runCatching { block() }.onFailure {
        currentCoroutineContext().ensureActive()
        log.warn("the preview comment could not be posted", it)
    }

    private fun find(projectId: UUID, number: Int) = PullRequests.selectAll().where { pullRequestKey(projectId, number) }.singleOrNull()?.toPullRequest()

    @Serializable
    data class Status(val missing: List<String>?, val pullRequests: List<PullRequest>)

    @Serializable
    data class Approval(val number: Int, val sha: String)
}
