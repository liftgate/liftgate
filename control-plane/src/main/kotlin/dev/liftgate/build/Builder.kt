package dev.liftgate.build

import dev.liftgate.App
import dev.liftgate.db.sql
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.events.Subject
import dev.liftgate.events.uuid
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.ScalableResource
import io.micrometer.core.instrument.Timer
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
private val digestPattern = Regex("sha256:[0-9a-f]{64}")
const val BUILD_CONSUMER = "builder-build-requested"

/**
 * @author Dean
 * @date 9/17/2026
 */
class Builder(private val app: App, private val kube: KubernetesClient) {
    private val log = LoggerFactory.getLogger(Builder::class.java)

    suspend fun build(buildId: UUID) {
        val build = app.builds.byId(buildId)?.takeIf { it.status == BuildStatus.QUEUED || it.status == BuildStatus.RUNNING } ?: return
        val scope = app.services.scope(build.serviceId) ?: return
        app.buildAdmission.admit(build, scope.org.id)?.let { return fail(buildId, it) }
        val github = app.github ?: return fail(buildId, "the GitHub App is not configured")
        val config = app.config
        val project = scope.project
        val image = BuildJobs.imageRef(config.registry, scope, build)
        val cache = BuildJobs.imageRef(config.registry, scope, "cache")
        val jobs = kube.batch().v1().jobs().inNamespace(config.buildNamespace)
        val sample = Timer.start()
        var pushed: Pair<String, String?>? = null
        val failure = try {
            val token = github.installationToken(project.installationId, project.repoFullName.substringAfter('/'))
            if (project.importedByLogin?.let { github.canPush(token, project.repoFullName, it) } == false) {
                "the GitHub account that imported ${project.repoFullName} no longer has write access; re-import it"
            } else {
                val spec = BuildJobSpec(
                    build, scope.service, project, token, image, cache, config.buildImage, config.buildNamespace,
                    config.buildNodeSelector, config.registryInsecure, config.registryTokenAuth, config.buildTolerations,
                    app.services.production(scope)?.let { BuildJobs.imageRef(config.registry, it, "cache") },
                    app.envVars.list(scope.service.id, reveal = true),
                )
                pushed = run(jobs.resource(BuildJobs.job(spec)), spec)
                "the build job failed".takeIf { pushed == null }
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            log.warn("build {} failed", buildId, e)
            e.message ?: "the build could not be run"
        }
        if (failure == null) {
            val (ref, digest) = checkNotNull(pushed)
            app.builds.markSucceeded(buildId, ref, digest)
            runCatching { jobs.withName(BuildJobs.name(buildId)).delete() }
        } else {
            app.builds.markFailed(buildId, failure)
        }
        sample.stop(app.metrics.timer("liftgate.build.duration", "status", (if (failure == null) BuildStatus.SUCCEEDED else BuildStatus.FAILED).sql))
        app.registryTokens.revoke(buildId)
        app.nats.logs.end(buildId, failure)
    }

    fun start(): Job = app.nats.consume(Subject.BUILD_REQUESTED, BUILD_CONSUMER, app.scope, Duration.ofSeconds(60), CONCURRENT_BUILDS) { build(it.uuid("buildId")) }

    private suspend fun fail(buildId: UUID, reason: String) {
        app.builds.markFailed(buildId, reason)
        app.nats.logs.end(buildId, reason)
    }

    private suspend fun run(job: ScalableResource<KubeJob>, spec: BuildJobSpec): Pair<String, String?>? = coroutineScope {
        val buildId = spec.build.id
        val owner = runInterruptible(Dispatchers.IO) { job.get() ?: job.create() }
        if (runInterruptible(Dispatchers.IO) { kube.secrets().inNamespace(spec.namespace).withName(BuildJobs.name(buildId)).get() } == null) {
            val password = if (spec.registryTokenAuth) app.registryTokens.issue(buildId) else null
            runInterruptible(Dispatchers.IO) { kube.resource(BuildJobs.tokenSecret(spec, owner, password)).create() }
        }
        val logs = launch(Dispatchers.IO) { runInterruptible { stream(buildId) } }
        val finished = runInterruptible(Dispatchers.IO) { job.waitUntilCondition({ it?.status?.run { (succeeded ?: 0) > 0 || (failed ?: 0) > 0 } == true }, WAIT_MINUTES, TimeUnit.MINUTES) }
        withTimeoutOrNull(logDrain) { logs.join() }
        logs.cancel()
        BuildJobs.imageRef(owner).takeIf { (finished.status.succeeded ?: 0) > 0 }?.let { it to runInterruptible(Dispatchers.IO) { digest(buildId) } }
    }

    private fun digest(buildId: UUID) = kube.pods().inNamespace(app.config.buildNamespace).withLabel(BUILD_LABEL, buildId.toString()).list().items
        .filter { it.status?.phase == "Succeeded" }
        .firstNotNullOfOrNull { pod -> pod.status.containerStatuses?.find { it.name == "build" }?.state?.terminated?.message?.trim()?.takeIf { digestPattern.matches(it) } }

    private fun stream(buildId: UUID) = runCatching {
        val pods = kube.pods().inNamespace(app.config.buildNamespace)
        val scheduled = pods.withLabel(BUILD_LABEL, buildId.toString()).informOnCondition { it.isNotEmpty() }
        val pod = try {
            scheduled.get(POD_WAIT_MINUTES, TimeUnit.MINUTES).first()
        } finally {
            scheduled.cancel(true)
        }
        pods.resource(pod).withReadyWaitTimeout(TimeUnit.MINUTES.toMillis(POD_WAIT_MINUTES).toInt()).watchLog().use { watch ->
            watch.output.bufferedReader().forEachLine { app.nats.logs.publish(buildId, it) }
        }
    }.onFailure { log.debug("log stream of build {} ended", buildId, it) }
}
