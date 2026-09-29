package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.deploy.DeploymentHealth
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.http.LiftgateException
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private val recordRetry = 5.seconds

/**
 * @author Dean
 * @date 9/17/2026
 */
data class Rollout(val deploymentId: UUID, val status: DeploymentStatus, val replicasReady: Int, val health: DeploymentHealth, val error: String? = null) {
    companion object {
        fun of(deployment: Deployment): Rollout? {
            val id = deployment.metadata.labels?.get(DEPLOYMENT_LABEL)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val status = deployment.status?.takeIf { (it.observedGeneration ?: 0) >= (deployment.metadata.generation ?: 0) } ?: return null
            val desired = deployment.spec.replicas ?: 1
            val ready = status.readyReplicas ?: 0
            val stalled = status.conditions.orEmpty().firstOrNull { it.type == "Progressing" && it.reason == "ProgressDeadlineExceeded" }
            val rolledOut = listOf(status.replicas, status.updatedReplicas, status.availableReplicas).all { (it ?: 0) == desired }
            val health = when {
                ready >= desired -> DeploymentHealth.HEALTHY
                ready == 0 -> DeploymentHealth.DOWN
                else -> DeploymentHealth.DEGRADED
            }
            return when {
                stalled != null -> Rollout(id, DeploymentStatus.FAILED, ready, health, stalled.message)
                rolledOut -> Rollout(id, DeploymentStatus.RUNNING, ready, health)
                else -> Rollout(id, DeploymentStatus.RELEASING, ready, health)
            }
        }
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
class DeploymentWatcher(private val app: App, private val kube: KubernetesClient, private val reconciler: Reconciler = Reconciler(app, kube)) {
    private val log = LoggerFactory.getLogger(DeploymentWatcher::class.java)
    private val rollouts = Channel<Rollout>(Channel.UNLIMITED)

    fun start(): SharedIndexInformer<Deployment> {
        app.scope.launch { for (rollout in rollouts) record(rollout) }
        return kube.apps().deployments().inAnyNamespace().withLabel(MANAGED_LABEL, "true").inform(
            object : ResourceEventHandler<Deployment> {
                override fun onAdd(deployment: Deployment) = observe(deployment)
                override fun onUpdate(old: Deployment, deployment: Deployment) {
                    if (old.metadata.resourceVersion != deployment.metadata.resourceVersion) observe(deployment)
                }
                override fun onDelete(deployment: Deployment, finalStateUnknown: Boolean) = Unit
            },
            0,
        )
    }

    private fun observe(deployment: Deployment) {
        Rollout.of(deployment)?.let(rollouts::trySend)
    }

    private suspend fun record(rollout: Rollout) {
        while (true) {
            try {
                transition(rollout)
                return app.deployments.observe(rollout.deploymentId, rollout.replicasReady, rollout.health)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (e !is SQLException && !(e is KubernetesClientException && e.retryable)) return log.warn("could not record rollout of deployment {}", rollout.deploymentId, e)
                log.warn("could not record rollout of deployment {}, retrying", rollout.deploymentId, e)
                delay(recordRetry)
            }
        }
    }

    private suspend fun transition(rollout: Rollout) = try {
        val fallback = if (rollout.status == DeploymentStatus.FAILED) app.deployments.fallback(rollout.deploymentId) else null
        app.deployments.transition(rollout.deploymentId, rollout.status, rollout.replicasReady, rollout.error)
        fallback?.let {
            reconciler.reapply(it.serviceId)
            app.deployments.transition(rollout.deploymentId, rollout.status, rollout.replicasReady, listOfNotNull(rollout.error, "reverted to ${it.id}").joinToString("; "))
        }
    } catch (e: LiftgateException) {
        log.debug("ignored rollout of deployment {}: {}", rollout.deploymentId, e.message)
    }
}
