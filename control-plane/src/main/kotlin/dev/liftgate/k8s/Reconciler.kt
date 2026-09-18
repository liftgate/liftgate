package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.deploy.Deployment
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.events.Subject
import dev.liftgate.events.uuid
import dev.liftgate.service.ServiceKind
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

/**
 * @author Dean
 * @date 9/17/2026
 */
class Reconciler(private val app: App, private val kube: KubernetesClient) {
    private val log = LoggerFactory.getLogger(Reconciler::class.java)

    suspend fun release(deploymentId: UUID) {
        val deployment = app.deployments.byId(deploymentId)?.takeIf { it.status.allows(DeploymentStatus.RELEASING) } ?: return
        if (app.deployments.forService(deployment.serviceId, limit = 1).single().id != deployment.id)
            return app.deployments.transition(deployment.id, DeploymentStatus.SUPERSEDED)
        val release = load(deployment) ?: return
        app.deployments.transition(deployment.id, DeploymentStatus.RELEASING)
        try {
            apply(release)
            if (release.service.kind == ServiceKind.CRON) app.deployments.transition(deployment.id, DeploymentStatus.RUNNING)
        } catch (e: KubernetesClientException) {
            log.warn("release of deployment {} failed", deployment.id, e)
            return app.deployments.transition(deployment.id, DeploymentStatus.FAILED, error = e.status?.message ?: e.message)
        }
        runCatching { syncCustomDomains() }.onFailure { currentCoroutineContext().ensureActive(); log.warn("custom domains could not be synced", it) }
    }

    suspend fun reroute(serviceId: UUID) {
        app.deployments.current(serviceId)?.let { load(it) }?.let { withContext(Dispatchers.IO) { route(it) } }
        syncCustomDomains()
    }

    suspend fun teardown(namespace: String, serviceId: UUID?) {
        withContext(Dispatchers.IO) {
            if (serviceId == null) kube.namespaces().withName(namespace).delete()
            else listOf(kube.apps().deployments(), kube.batch().v1().cronjobs(), kube.services(), kube.secrets(), kube.resources(HTTPRoute::class.java))
                .forEach { it.inNamespace(namespace).withLabel(SERVICE_ID_LABEL, serviceId.toString()).delete() }
        }
        syncCustomDomains()
    }

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
        )
    }

    private suspend fun apply(r: Release) = withContext(Dispatchers.IO) {
        val config = app.config
        val workloads = listOf(Resources.deployment(r, config.runtimeClass), Resources.cronJob(r, config.runtimeClass))
        val (workload, otherWorkload) = if (r.service.kind == ServiceKind.CRON) workloads.reversed() else workloads
        (listOf(Resources.namespace(r), Resources.resourceQuota(r), Resources.secret(r)) + Resources.networkPolicies(r, config.gatewayNamespace) + workload)
            .forEach { kube.resource(it).apply() }
        route(r)
        kube.resource(otherWorkload).delete()
    }

    private fun route(r: Release) = listOf(Resources.service(r), Resources.httpRoute(r, app.config.gatewayNamespace, app.config.gatewayName))
        .forEach { if (r.routable) kube.resource(it).apply() else kube.resource(it).delete() }

    private suspend fun syncCustomDomains() {
        val config = app.config
        val domains = app.domains.verifiedCustom()
        withContext(Dispatchers.IO) {
            kube.resources(Gateway::class.java).inNamespace(config.gatewayNamespace).withName(config.gatewayName).get() ?: return@withContext
            val certificates = kube.genericKubernetesResources(certificateContext).inNamespace(config.gatewayNamespace)
            domains.forEach { certificates.resource(Resources.certificate(it, config.gatewayNamespace)).apply() }
            kube.resource(Resources.gatewayListeners(domains, config.gatewayNamespace, config.gatewayName)).apply()
            certificates.withLabel(MANAGED_LABEL, "true").list().items
                .filter { certificate -> domains.none { it.hostname == certificate.metadata.name } }
                .forEach { certificates.resource(it).delete() }
        }
    }
}
