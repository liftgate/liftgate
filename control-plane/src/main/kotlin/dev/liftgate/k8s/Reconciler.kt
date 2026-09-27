package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.deploy.Deployment
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.events.Subject
import dev.liftgate.events.uuid
import dev.liftgate.service.ServiceKind
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.batch.v1.CronJob
import io.fabric8.kubernetes.api.model.gatewayapi.v1.Gateway
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID
import io.fabric8.kubernetes.api.model.apps.Deployment as KubeDeployment

/**
 * @author Dean
 * @date 9/17/2026
 */
class Reconciler(private val app: App, private val kube: KubernetesClient) {
    private val log = LoggerFactory.getLogger(Reconciler::class.java)

    suspend fun release(deploymentId: UUID) {
        val deployment = app.deployments.byId(deploymentId) ?: return
        val newest = app.deployments.forService(deployment.serviceId, limit = 1).singleOrNull() ?: return
        val running = deployment.status == DeploymentStatus.RUNNING && newest.id == deployment.id
        if (!running && !deployment.status.allows(DeploymentStatus.RELEASING)) return
        if (newest.id != deployment.id) return app.deployments.transition(deployment.id, DeploymentStatus.SUPERSEDED)
        val release = load(deployment) ?: return
        if (overtaken(release)) return app.deployments.transition(deployment.id, DeploymentStatus.SUPERSEDED)
        if (!running) app.deployments.transition(deployment.id, DeploymentStatus.RELEASING)
        try {
            apply(release)
        } catch (e: KubernetesClientException) {
            if (e.code !in 400..499 || e.code == 409 || e.code == 429) throw e
            log.warn("release of deployment {} failed", deployment.id, e)
            return app.deployments.transition(deployment.id, DeploymentStatus.FAILED, error = e.status?.message ?: e.message)
        }
        if (converge(deployment)) return app.deployments.transition(deployment.id, DeploymentStatus.SUPERSEDED)
        if (release.service.kind == ServiceKind.CRON) app.deployments.transition(deployment.id, DeploymentStatus.RUNNING)
        runCatching { syncCustomDomains() }.onFailure { currentCoroutineContext().ensureActive(); log.warn("custom domains could not be synced", it) }
    }

    suspend fun reroute(serviceId: UUID) {
        app.deployments.current(serviceId)?.let { load(it) }?.let { withContext(Dispatchers.IO) { route(it) } }
        syncCustomDomains()
    }

    suspend fun reapply(serviceId: UUID, live: HasMetadata? = null) {
        val latest = app.deployments.forService(serviceId, limit = 1).singleOrNull() ?: return
        val release = load(latest.takeIf { it.status == DeploymentStatus.RELEASING } ?: app.deployments.current(serviceId) ?: latest) ?: return
        if (live != null && (live.stopped() || !release.suspended) &&
            (live.metadata.labels?.get(DEPLOYMENT_LABEL) == release.deployment.id.toString() || overtaken(release, live))
        ) return withContext(Dispatchers.IO) { environment(release).forEach { kube.resource(it).apply() } }
        apply(release)
    }

    suspend fun teardown(namespace: String, serviceId: UUID?) {
        withContext(Dispatchers.IO) {
            if (serviceId == null) kube.namespaces().withName(namespace).delete()
            else (serviceObjects() + kube.secrets()).forEach { it.inNamespace(namespace).withLabel(SERVICE_ID_LABEL, serviceId.toString()).delete() }
        }
        syncCustomDomains()
    }

    fun serviceObjects() = listOf(kube.apps().deployments(), kube.batch().v1().cronjobs(), kube.services(), kube.resources(HTTPRoute::class.java))

    fun start(): Job {
        val consumers = CoroutineScope(app.scope.coroutineContext + SupervisorJob(app.scope.coroutineContext.job))
        app.nats.consume(Subject.RELEASE_REQUESTED, "reconciler-release-requested", consumers) { release(it.uuid("deploymentId")) }
        app.nats.consume(Subject.DOMAIN_VERIFY_REQUESTED, "reconciler-domain-verify-requested", consumers) { reroute(it.uuid("serviceId")) }
        app.nats.consume(Subject.TEARDOWN_REQUESTED, "reconciler-teardown-requested", consumers) {
            teardown(it.getValue("namespace").jsonPrimitive.content, it["serviceId"]?.jsonPrimitive?.content?.let(UUID::fromString))
        }
        return consumers.coroutineContext.job
    }

    private suspend fun load(deployment: Deployment): Release? {
        val scope = app.services.scope(deployment.serviceId) ?: return null
        val build = app.builds.byId(deployment.buildId) ?: return null
        return Release(
            deployment, build, scope.service, scope.environment, scope.project, scope.org,
            app.envVars.list(scope.service.id, reveal = true),
            app.domains.forService(scope.service.id).filter { it.verifiedAt != null },
            app.config.plans.of(scope.org.plan),
        )
    }

    private suspend fun overtaken(r: Release, live: HasMetadata? = null): Boolean {
        val workload: HasMetadata = if (r.service.kind == ServiceKind.CRON) Resources.cronJob(r, null) else Resources.deployment(r, null)
        val id = (live ?: withContext(Dispatchers.IO) { kube.resource(workload).get() })?.metadata?.labels?.get(DEPLOYMENT_LABEL)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.takeIf { it != r.deployment.id } ?: return false
        return app.deployments.byId(id)?.createdAt?.isAfter(r.deployment.createdAt) == true
    }

    private fun HasMetadata.stopped() = (this as? KubeDeployment)?.spec?.replicas == 0 || (this as? CronJob)?.spec?.suspend == true

    private suspend fun converge(deployment: Deployment): Boolean {
        val newest = app.deployments.forService(deployment.serviceId, limit = 1).singleOrNull()
            ?.takeIf { it.id != deployment.id && it.status != DeploymentStatus.FAILED } ?: return false
        load(newest)?.let { apply(it) }
        return true
    }

    private suspend fun apply(r: Release) = withContext(Dispatchers.IO) {
        val config = app.config
        val workloads = with(config) {
            listOf(Resources.deployment(r, runtimeClass, workloadNodeSelector, workloadTolerations), Resources.cronJob(r, runtimeClass, workloadNodeSelector, workloadTolerations))
        }
        val (workload, otherWorkload) = if (r.service.kind == ServiceKind.CRON) workloads.reversed() else workloads
        val setup = environment(r) + Resources.secret(r)
        if (r.suspended) stop(r, workload) else (setup + workload).forEach { kube.resource(it).apply() }
        route(r)
        kube.resource(otherWorkload).delete()
        if (r.suspended) setup.forEach { kube.resource(it).apply() }
    }

    private fun environment(r: Release): List<HasMetadata> = with(app.config) {
        listOf(Resources.namespace(r), Resources.resourceQuota(r)) + Resources.networkPolicies(r, gatewayNamespace) +
            listOfNotNull(logReaderRole?.let { Resources.logReaderBinding(r, it, logReaderAccount, kube.namespace) })
    }

    private fun stop(r: Release, workload: HasMetadata) {
        try {
            kube.resource(workload).apply()
        } catch (e: KubernetesClientException) {
            log.warn("deleting the workload of suspended service {} because it could not be applied", r.service.id, e)
            kube.resource(workload).delete()
        }
        if (r.service.kind == ServiceKind.CRON) stopJobs(r)
    }

    private fun stopJobs(r: Release) = kube.batch().v1().jobs().inNamespace(r.namespace).list().items
        .filter { job -> job.metadata.ownerReferences.orEmpty().any { it.kind == "CronJob" && it.name == r.service.slug } }
        .filter { job -> job.status?.conditions.orEmpty().none { it.status == "True" && it.type in setOf("Complete", "Failed") } }
        .forEach { kube.resource(it).delete() }

    private fun route(r: Release) = listOf(Resources.service(r), Resources.httpRoute(r, app.config.gatewayNamespace, app.config.gatewayName))
        .forEach { if (r.routable) kube.resource(it).apply() else kube.resource(it).delete() }

    suspend fun syncCustomDomains() {
        val config = app.config
        val domains = app.domains.verifiedCustom()
        withContext(Dispatchers.IO) {
            kube.resources(Gateway::class.java).inNamespace(config.gatewayNamespace).withName(config.gatewayName).get() ?: return@withContext
            val certificates = kube.genericKubernetesResources(certificateContext).inNamespace(config.gatewayNamespace)
            domains.forEach { certificates.resource(Resources.certificate(it, config.gatewayNamespace, config.certIssuer)).apply() }
            kube.resource(Resources.gatewayListeners(domains, config.gatewayNamespace, config.gatewayName)).apply()
            certificates.withLabel(MANAGED_LABEL, "true").list().items
                .filter { certificate -> domains.none { it.hostname == certificate.metadata.name } }
                .forEach { certificates.resource(it).delete() }
        }
    }
}
