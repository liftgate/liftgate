package dev.liftgate.events

import dev.liftgate.config.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfigBuilder
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
class LeaderElection(private val config: Config, private val kube: KubernetesClient, private val lease: String) {
    private val log = LoggerFactory.getLogger(LeaderElection::class.java)
    private val identity = System.getenv("HOSTNAME") ?: UUID.randomUUID().toString()

    fun start(scope: CoroutineScope, onLead: suspend () -> Unit): Job = scope.launch {
        if (!config.leaderElection) return@launch onLead()
        while (isActive) {
            campaign(onLead)
            delay(1000)
        }
    }

    private suspend fun CoroutineScope.campaign(onLead: suspend () -> Unit) {
        var lead: Job? = null
        val callbacks = LeaderCallbacks({ lead = launch { onLead() } }, { lead?.cancel() }, { log.info("{} leader is {}", lease, it) })
        val election = kube.leaderElector().withConfig(
            LeaderElectionConfigBuilder()
                .withName(lease)
                .withLock(LeaseLock(kube.namespace ?: "default", lease, identity))
                .withLeaseDuration(Duration.ofSeconds(15))
                .withRenewDeadline(Duration.ofSeconds(10))
                .withRetryPeriod(Duration.ofSeconds(2))
                .withReleaseOnCancel()
                .withLeaderCallbacks(callbacks)
                .build(),
        ).build().start()
        try {
            election.await()
        } catch (e: Exception) {
            ensureActive()
            log.warn("leader election failed", e)
        } finally {
            election.cancel(true)
            lead?.cancel()
        }
    }
}
