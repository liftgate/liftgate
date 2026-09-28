package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.build.BUILD_CONSUMER
import dev.liftgate.build.BUILD_LABEL
import dev.liftgate.db.Deployments
import dev.liftgate.db.Environments
import dev.liftgate.db.Services
import dev.liftgate.db.sql
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.live
import dev.liftgate.events.LeaderElection
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.RowNumber
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val KEPT_SECRETS = 5

/**
 * @author Dean
 * @date 9/27/2026
 */
class Sweeper(private val app: App, private val kube: KubernetesClient) {
    private val log = LoggerFactory.getLogger(Sweeper::class.java)
    private val reconciler = Reconciler(app, kube)

    fun start(): Job = LeaderElection(app.config, kube, "liftgate-sweeper").start(app.scope) {
        coroutineScope {
            every(1.minutes, "redrive", ::redrive)
            every(1.minutes, "orphan removal", ::removeOrphans)
            every(5.minutes, "resync", ::resync)
            every(5.minutes, "env secret pruning", ::pruneSecrets)
        }
    }

    suspend fun redrive() {
        app.deployments.redrive()
        app.builds.timeOut(BuildStatus.RUNNING)
        if (app.nats.backlog(BUILD_CONSUMER) > 0) return
        app.builds.timeOut(BuildStatus.QUEUED)
        val jobs = withContext(Dispatchers.IO) { kube.batch().v1().jobs().inNamespace(app.config.buildNamespace).withLabel(BUILD_LABEL).list().items }
        app.builds.requeue(jobs.mapNotNull { it.metadata.labels[BUILD_LABEL] }.toSet())
    }

    suspend fun removeOrphans() {
        val namespaces = withContext(Dispatchers.IO) { kube.namespaces().withLabel(MANAGED_LABEL, "true").withLabel(ORG_ID_LABEL).list().items }
            .filter { it.metadata.deletionTimestamp == null }.map { it.metadata.name }
        val environments = app.db.tx { Environments.select(Environments.namespace).map { it[Environments.namespace] }.toSet() }
        if (environments.isEmpty()) return
        namespaces.filterNot { it in environments }.each("teardown of namespace") { reconciler.teardown(it, null) }
        val labelled = withContext(Dispatchers.IO) { reconciler.serviceObjects().flatMap { it.inAnyNamespace().withLabel(MANAGED_LABEL, "true").list().items } }
        val services = app.db.tx { Services.select(Services.id).map { it[Services.id].toString() }.toSet() }
        labelled.mapNotNull { resource -> resource.metadata.labels[SERVICE_ID_LABEL]?.let { resource.metadata.namespace to it } }.distinct()
            .filter { (namespace, service) -> namespace in environments && service !in services }
            .each("teardown of service") { (namespace, service) -> reconciler.teardown(namespace, UUID.fromString(service)) }
    }

    suspend fun resync() {
        val live = withContext(Dispatchers.IO) { workloads() }.groupBy { it.metadata.labels[SERVICE_ID_LABEL] }.mapValues { it.value.singleOrNull() }
        val running = app.db.tx {
            Deployments.select(Deployments.serviceId).where { Deployments.status eq DeploymentStatus.RUNNING.sql }.withDistinct().map { it[Deployments.serviceId] }
        }
        running.each("resync of service") { reconciler.reapply(it, live[it.toString()]) }
        reconciler.syncCustomDomains()
    }

    suspend fun pruneSecrets() {
        val recency = RowNumber().over().partitionBy(Deployments.serviceId).orderBy(Deployments.createdAt, SortOrder.DESC).alias("recency")
        val ranked = Deployments.select(Deployments.id, Deployments.serviceId, Deployments.status, recency).alias("ranked")
        val (id, status, rank) = Triple(ranked[Deployments.id], ranked[Deployments.status], ranked[recency])
        val services = app.db.tx {
            ranked.join(Services, JoinType.INNER, ranked[Deployments.serviceId], Services.id).innerJoin(Environments)
                .select(Environments.namespace, Services.id, id, status, rank)
                .where { (rank lessEq KEPT_SECRETS + 1L) or (status inList live) }
                .groupBy({ it[Environments.namespace] to it[Services.id].toString() }, { it[id].toString().takeIf { _ -> it[rank] <= KEPT_SECRETS || it[status] in live } })
        }
        services.filterValues { it.size > KEPT_SECRETS }.entries.toList().each("pruning of the env secrets of") { (service, kept) ->
            withContext(Dispatchers.IO) {
                kube.secrets().inNamespace(service.first).withLabel(SERVICE_ID_LABEL, service.second).withLabel(DEPLOYMENT_LABEL)
                    .withLabelNotIn(DEPLOYMENT_LABEL, *kept.filterNotNull().toTypedArray()).delete()
            }
        }
    }

    private fun workloads(): List<HasMetadata> = kube.apps().deployments().inAnyNamespace().withLabel(MANAGED_LABEL, "true").list().items +
        kube.batch().v1().cronjobs().inAnyNamespace().withLabel(MANAGED_LABEL, "true").list().items

    private suspend fun <T> List<T>.each(action: String, block: suspend (T) -> Unit) = forEach {
        runCatching { block(it) }.onFailure { e -> currentCoroutineContext().ensureActive(); log.warn("{} {} failed", action, it, e) }
    }

    private fun CoroutineScope.every(period: Duration, task: String, block: suspend () -> Unit) = launch {
        while (true) {
            runCatching { block() }.onFailure { ensureActive(); log.warn("{} failed", task, it) }
            delay(period)
        }
    }
}
