package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.http.LiftgateException
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID

private const val RESYNC_MILLIS = 60_000L

/**
 * @author Dean
 * @date 9/17/2026
 */
data class Rollout(val deploymentId: UUID, val status: DeploymentStatus, val replicasReady: Int, val error: String? = null) {
    companion object {
        fun of(deployment: Deployment): Rollout? {
            val id = deployment.metadata.labels?.get(DEPLOYMENT_LABEL)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val status = deployment.status?.takeIf { (it.observedGeneration ?: 0) >= (deployment.metadata.generation ?: 0) } ?: return null
            val desired = deployment.spec.replicas ?: 1
            val ready = status.readyReplicas ?: 0
            val stalled = status.conditions.orEmpty().firstOrNull { it.type == "Progressing" && it.reason == "ProgressDeadlineExceeded" }
            val rolledOut = listOf(status.replicas, status.updatedReplicas, status.availableReplicas).all { (it ?: 0) == desired }
            return when {
                stalled != null -> Rollout(id, DeploymentStatus.FAILED, ready, stalled.message)
                rolledOut -> Rollout(id, DeploymentStatus.RUNNING, ready)
                else -> Rollout(id, DeploymentStatus.RELEASING, ready)
            }
        }
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
class DeploymentWatcher(private val app: App, private val kube: KubernetesClient) {
    private val log = LoggerFactory.getLogger(DeploymentWatcher::class.java)
    private val rollouts = Channel<Rollout>(Channel.UNLIMITED)

    fun start(): SharedIndexInformer<Deployment> {
        app.scope.launch { for (rollout in rollouts) record(rollout) }
        return kube.apps().deployments().inAnyNamespace().withLabel(MANAGED_LABEL, "true").inform(
            object : ResourceEventHandler<Deployment> {
                override fun onAdd(deployment: Deployment) = observe(deployment)
                override fun onUpdate(old: Deployment, deployment: Deployment) = observe(deployment)
                override fun onDelete(deployment: Deployment, finalStateUnknown: Boolean) = Unit
            },
            RESYNC_MILLIS,
        )
    }

    private fun observe(deployment: Deployment) {
        Rollout.of(deployment)?.let(rollouts::trySend)
    }

    private suspend fun record(rollout: Rollout) = try {
        app.deployments.transition(rollout.deploymentId, rollout.status, rollout.replicasReady, rollout.error)
    } catch (e: LiftgateException) {
        log.debug("ignored rollout of deployment {}: {}", rollout.deploymentId, e.message)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        log.warn("could not record rollout of deployment {}", rollout.deploymentId, e)
    }
}
