package dev.liftgate.build

import dev.liftgate.App
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.events.Subject
import dev.liftgate.events.buildLogSubject
import dev.liftgate.events.uuid
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation
import io.fabric8.kubernetes.client.dsl.ScalableResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import io.fabric8.kubernetes.api.model.batch.v1.Job as KubeJob

private const val WAIT_MINUTES = 35L
private const val CONCURRENT_BUILDS = 4
private const val POD_WAIT_MINUTES = 10L
private val logDrain = 10.seconds

/**
 * @author Dean
 * @date 9/17/2026
 */
class Builder(private val app: App, private val kube: KubernetesClient) {
    private val log = LoggerFactory.getLogger(Builder::class.java)

    suspend fun build(buildId: UUID) {
        val build = app.builds.byId(buildId)?.takeIf { it.status == BuildStatus.QUEUED || it.status == BuildStatus.RUNNING } ?: return
        val scope = app.services.scope(build.serviceId) ?: return
        val github = app.github ?: return app.builds.markFailed(buildId, "the GitHub App is not configured")
        val config = app.config
        val image = BuildJobs.imageRef(config.registry, scope.org, scope.project, scope.service, build.commitSha)
        val cache = BuildJobs.imageRef(config.registry, scope.org, scope.project, scope.service, "cache")
        app.builds.markRunning(buildId)
        val failure = try {
            val token = github.installationToken(scope.project.installationId)
            val spec = BuildJobSpec(build, scope.service, scope.project, token, image, cache, config.buildImage, config.buildNamespace)
            "the build job failed".takeUnless { run(kube.batch().v1().jobs().inNamespace(config.buildNamespace).resource(BuildJobs.job(spec)), spec) }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            log.warn("build {} failed", buildId, e)
            e.message ?: "the build could not be run"
        }
        if (failure == null) app.builds.markSucceeded(buildId, image) else app.builds.markFailed(buildId, failure)
    }

    fun start(): Job = app.nats.consume(Subject.BUILD_REQUESTED, "builder-build-requested", app.scope, Duration.ofMinutes(WAIT_MINUTES), CONCURRENT_BUILDS) { build(it.uuid("buildId")) }

    private suspend fun run(job: ScalableResource<KubeJob>, spec: BuildJobSpec): Boolean = coroutineScope {
        val buildId = spec.build.id
        runInterruptible(Dispatchers.IO) { kube.resource(BuildJobs.tokenSecret(spec, job.get() ?: job.create())).createOr(NonDeletingOperation<Secret>::update) }
        val logs = launch(Dispatchers.IO) { runInterruptible { stream(buildId) } }
        val finished = runInterruptible(Dispatchers.IO) { job.waitUntilCondition({ it?.status?.run { (succeeded ?: 0) > 0 || (failed ?: 0) > 0 } == true }, WAIT_MINUTES, TimeUnit.MINUTES) }
        withTimeoutOrNull(logDrain) { logs.join() }
        logs.cancel()
        ((finished.status.succeeded ?: 0) > 0).also { succeeded -> if (succeeded) runCatching { job.delete() } }
    }

    private fun stream(buildId: UUID) = runCatching {
        val pods = kube.pods().inNamespace(app.config.buildNamespace)
        val scheduled = pods.withLabel(BUILD_LABEL, buildId.toString()).informOnCondition { it.isNotEmpty() }
        val pod = try {
            scheduled.get(POD_WAIT_MINUTES, TimeUnit.MINUTES).first()
        } finally {
            scheduled.cancel(true)
        }
        pods.resource(pod).withReadyWaitTimeout(TimeUnit.MINUTES.toMillis(POD_WAIT_MINUTES).toInt()).watchLog().use { watch ->
            watch.output.bufferedReader().forEachLine { app.nats.publishLog(buildLogSubject(buildId), it) }
        }
    }.onFailure { log.debug("log stream of build {} ended", buildId, it) }
}
