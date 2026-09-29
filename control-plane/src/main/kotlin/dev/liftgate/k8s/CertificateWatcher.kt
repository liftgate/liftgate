package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.domain.CertificateState
import dev.liftgate.events.LeaderElection
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val edgePoll = 30.seconds
private val resync = 5.minutes

/**
 * @author Dean
 * @date 9/27/2026
 */
class CertificateWatcher(private val app: App, private val kube: KubernetesClient) {
    private val log = LoggerFactory.getLogger(CertificateWatcher::class.java)
    private val states = Channel<Pair<String, CertificateState>>(Channel.UNLIMITED)

    fun start() {
        if (app.config.cloudflare == null) watch() else poll()
    }

    fun watch(): SharedIndexInformer<GenericKubernetesResource> {
        app.scope.launch {
            for ((hostname, state) in states) {
                runCatching { app.domains.certificate(hostname, state) }.onFailure { ensureActive(); log.warn("could not record the certificate of {}", hostname, it) }
            }
        }
        return kube.genericKubernetesResources(certificateContext).inNamespace(app.config.gatewayNamespace).withLabel(MANAGED_LABEL, "true").inform(
            object : ResourceEventHandler<GenericKubernetesResource> {
                override fun onAdd(certificate: GenericKubernetesResource) = observe(certificate)
                override fun onUpdate(old: GenericKubernetesResource, certificate: GenericKubernetesResource) = observe(certificate)
                override fun onDelete(certificate: GenericKubernetesResource, finalStateUnknown: Boolean) = Unit
            },
            resync.inWholeMilliseconds,
        )
    }

    fun poll(): Job = LeaderElection(app.config, kube, "liftgate-domains").start(app.scope) {
        while (true) {
            runCatching { app.domains.refreshEdge() }.onFailure { currentCoroutineContext().ensureActive(); log.warn("edge certificates could not be refreshed", it) }
            delay(edgePoll)
        }
    }

    private fun observe(certificate: GenericKubernetesResource) {
        states.trySend(certificate.metadata.name to state(certificate))
    }

    companion object {
        fun state(certificate: GenericKubernetesResource): CertificateState {
            val conditions = certificate.get<List<Map<String, Any?>>>("status", "conditions").orEmpty()
            val ready = conditions.firstOrNull { it["type"] == "Ready" }
            val issuing = conditions.firstOrNull { it["type"] == "Issuing" }
            return when {
                ready?.get("status") == "True" -> CertificateState("ready")
                certificate.get<Any>("status", "lastFailureTime") != null -> CertificateState("failed", (issuing ?: ready)?.get("message")?.toString())
                else -> CertificateState("pending", ready?.get("message")?.toString())
            }
        }
    }
}
