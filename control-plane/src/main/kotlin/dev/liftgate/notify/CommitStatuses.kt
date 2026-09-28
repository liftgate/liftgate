package dev.liftgate.notify

import dev.liftgate.App
import dev.liftgate.build.GitHubApp
import dev.liftgate.deploy.Build
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Deployment
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.events.Subject
import dev.liftgate.events.changed
import dev.liftgate.events.uuid
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.util.UUID

private const val MAX_DESCRIPTION = 140

/**
 * @author Dean
 * @date 9/27/2026
 */
class CommitStatuses(private val app: App) {
    private val log = LoggerFactory.getLogger(CommitStatuses::class.java)

    fun start() {
        if (app.github == null) return
        app.nats.consume(Subject.BUILD_REQUESTED, "api-status-build-requested", app.scope) { post(it.uuid("buildId")) }
        app.nats.consume(Subject.BUILD_COMPLETED, "api-status-build-completed", app.scope) { post(it.uuid("buildId")) }
        app.nats.consume(Subject.DEPLOYMENT_UPDATED, "api-status-deployment-updated", app.scope) {
            if (it.changed) app.deployments.byId(it.uuid("deploymentId"))?.let { deployment -> post(deployment.buildId) }
        }
    }

    suspend fun post(buildId: UUID) {
        val github = app.github ?: return
        var posted: GitHubApp.CommitStatus? = null
        while (true) {
            val requested = app.builds.byId(buildId) ?: return
            val builds = app.builds.forService(requested.serviceId).filter { it.commitSha == requested.commitSha } + requested
            val deployment = app.deployments.forService(requested.serviceId).firstOrNull { d -> d.createdAt >= builds.first().createdAt && builds.any { it.id == d.buildId } }
            val build = builds.firstOrNull { it.id == deployment?.buildId } ?: builds.first()
            val scope = app.services.scope(build.serviceId) ?: return
            val (state, description) = state(build, deployment)
            val status = GitHubApp.CommitStatus(state, scope.buildUrl(app.config.dashboardUrl, build.id), description.take(MAX_DESCRIPTION), "liftgate/${scope.service.slug}")
            if (status == posted) return
            try {
                val project = scope.project
                val token = github.installationToken(project.installationId, project.repoFullName.substringAfter('/'), mapOf("statuses" to "write", "metadata" to "read"))
                if (project.importedByLogin?.let { github.canPush(token, project.repoFullName, it) } == false) return
                github.postStatus(token, project.repoFullName, build.commitSha, status)
            } catch (e: ClientRequestException) {
                val headers = e.response.headers
                if (e.response.status == HttpStatusCode.TooManyRequests || headers["x-ratelimit-remaining"] == "0" || headers[HttpHeaders.RetryAfter] != null) throw e
                return log.warn("GitHub refused the commit status of build {}: {}", build.id, e.response.status)
            }
            posted = status
        }
    }

    private fun state(build: Build, deployment: Deployment?): Pair<String, String> = when (deployment?.status) {
        null -> when (build.status) {
            BuildStatus.QUEUED -> "pending" to "Queued"
            BuildStatus.RUNNING -> "pending" to "Building"
            BuildStatus.SUCCEEDED -> "success" to "Built, and a newer commit was deployed"
            BuildStatus.FAILED -> "failure" to "Build failed: ${build.error}"
            BuildStatus.CANCELLED -> "error" to "Cancelled by a newer push"
        }
        DeploymentStatus.PENDING, DeploymentStatus.RELEASING -> "pending" to "Deploying"
        DeploymentStatus.RUNNING -> "success" to "Deployed"
        DeploymentStatus.FAILED -> "failure" to "Deployment failed" + deployment.error?.let { ": $it" }.orEmpty()
        DeploymentStatus.SUPERSEDED, DeploymentStatus.ROLLED_BACK -> "success" to "Replaced by a newer deployment"
    }
}
