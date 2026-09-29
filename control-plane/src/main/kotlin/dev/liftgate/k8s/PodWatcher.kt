package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.http.LiftgateException
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.sql.SQLTransientException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val MAX_RESTARTS = 3
private val stuck = setOf("CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "CreateContainerConfigError")
private val unreleased = setOf(DeploymentStatus.PENDING, DeploymentStatus.RELEASING)
private val failRetry = 5.seconds
private val SQLException.retryable get() = generateSequence<Throwable>(this) { it.cause }.any { it is SQLTransientException || it is SQLException && it.sqlState.orEmpty().startsWith("08") }

/**
 * @author Dean
 * @date 9/27/2026
 */
class PodWatcher(private val app: App, private val kube: KubernetesClient) {
    private val log = LoggerFactory.getLogger(PodWatcher::class.java)
    private val failures = Channel<Pair<UUID, String>>(Channel.UNLIMITED)

    fun start(): SharedIndexInformer<Pod> {
        app.scope.launch {
            for ((id, error) in failures) runCatching { fail(id, error) }.onFailure { ensureActive(); log.warn("could not fail deployment {}", id, it) }
        }
        return kube.pods().inAnyNamespace().withLabel(MANAGED_LABEL, "true").withLabel(DEPLOYMENT_LABEL).inform(
            object : ResourceEventHandler<Pod> {
                override fun onAdd(pod: Pod) = observe(pod)
                override fun onUpdate(old: Pod, pod: Pod) = observe(pod)
                override fun onDelete(pod: Pod, finalStateUnknown: Boolean) = Unit
            },
            0,
        )
    }

    private fun observe(pod: Pod) {
        val id = pod.metadata.labels?.get(DEPLOYMENT_LABEL)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        failure(pod)?.let { failures.trySend(id to it) }
    }

    private suspend fun fail(id: UUID, error: String) {
        while (true) {
            try {
                return app.deployments.transition(id, DeploymentStatus.FAILED, error = error, from = unreleased)
            } catch (e: LiftgateException) {
                return log.debug("ignored failure of deployment {}: {}", id, e.message)
            } catch (e: SQLException) {
                if (!e.retryable) throw e
                log.warn("could not fail deployment {}, retrying", id, e)
                delay(failRetry)
            }
        }
    }

    companion object {
        fun failure(pod: Pod): String? {
            val container = pod.status?.containerStatuses?.firstOrNull() ?: return null
            val waiting = container.state?.waiting
            val restarts = container.restartCount ?: 0
            val reason = waiting?.reason?.takeIf { it in stuck } ?: "restarted $restarts times".takeIf { restarts >= MAX_RESTARTS } ?: return null
            val terminated = container.lastState?.terminated ?: container.state?.terminated
            val message = (if (terminated != null) terminated.message else waiting?.message)?.filter { it == '\n' || !it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }
            val summary = listOfNotNull(reason, terminated?.let { "exit code ${it.exitCode}" }).joinToString(", ")
            return message?.let { "$summary: $it" } ?: summary
        }
    }
}
