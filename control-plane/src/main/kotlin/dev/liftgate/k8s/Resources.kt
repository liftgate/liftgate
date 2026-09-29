package dev.liftgate.k8s

import dev.liftgate.deploy.Build
import dev.liftgate.deploy.Deployment
import dev.liftgate.domain.Domain
import dev.liftgate.org.Organization
import dev.liftgate.org.Plan
import dev.liftgate.project.Environment
import dev.liftgate.project.Project
import dev.liftgate.service.EnvVar
import dev.liftgate.service.Service
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.LabelSelector
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.Probe
import io.fabric8.kubernetes.api.model.ProbeBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceQuota
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.Toleration
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJob
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.Gateway
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.Listener
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeerBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPort
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPortBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBinding
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import java.util.Base64
import java.util.UUID
import kotlin.math.roundToInt
import io.fabric8.kubernetes.api.model.EnvVar as KubeEnvVar
import io.fabric8.kubernetes.api.model.Service as KubeService
import io.fabric8.kubernetes.api.model.apps.Deployment as KubeDeployment

const val MANAGED_LABEL = "liftgate.dev/managed"
const val SERVICE_LABEL = "liftgate.dev/service"
const val SERVICE_ID_LABEL = "liftgate.dev/service-id"
const val ORG_ID_LABEL = "liftgate.dev/org-id"
const val DEPLOYMENT_LABEL = "liftgate.dev/deployment"
const val DOMAIN_LISTENER = "domain-"
val HasMetadata.deploymentId get() = metadata.labels?.get(DEPLOYMENT_LABEL)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
val certificateContext: ResourceDefinitionContext = ResourceDefinitionContext.Builder()
    .withGroup("cert-manager.io").withVersion("v1").withKind("Certificate").withPlural("certificates").withNamespaced(true)
    .build()

/**
 * @author Dean
 * @date 9/17/2026
 */
data class Release(
    val deployment: Deployment,
    val build: Build,
    val service: Service,
    val environment: Environment,
    val project: Project,
    val org: Organization,
    val envVars: List<EnvVar>,
    val domains: List<Domain>,
    val plan: Plan = Plan(),
) {
    val namespace get() = environment.namespace
    val hostnames get() = domains.map { it.hostname }
    val suspended get() = org.suspendedAt != null
    val routable get() = !suspended && service.kind.servesHttp && domains.isNotEmpty()
    val exposed get() = !suspended && service.listens
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object Resources {
    private const val DEFAULT_WEB_PORT = 8080
    private const val SERVICE_PORT = 80
    private const val HTTPS_PORT = 443
    private const val TENANT_UID = 1000L
    private const val MAX_PORT = 65535
    private const val ROLLOUT_HEADROOM = 2
    private const val PRE_STOP_SECONDS = 5L
    private const val MIN_READY_SECONDS = 10
    private const val GRACE_SECONDS = 30L
    private const val CRON_START_DEADLINE_SECONDS = 300L
    val privateRanges = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16")
    private val tcpWithoutSmtp = listOf(1 to 24, 26 to 464, 466 to 586, 588 to 2524, 2526 to MAX_PORT)

    fun namespace(r: Release): Namespace = NamespaceBuilder()
        .withMetadata(meta(r.namespace, null, r.environmentLabels() + ("pod-security.kubernetes.io/enforce" to "restricted")))
        .build()

    fun resourceQuota(r: Release): ResourceQuota {
        val pods = r.plan.replicas?.let { it * ROLLOUT_HEADROOM }
        val disk = pods?.let { Quantity("${it * r.plan.ephemeralMb}Mi") }
        val hard = mapOf(
            "pods" to pods?.let { Quantity("$it") },
            "limits.cpu" to r.plan.cpuMillis?.let { Quantity("${it * ROLLOUT_HEADROOM}m") },
            "limits.memory" to r.plan.memoryMb?.let { Quantity("${it * ROLLOUT_HEADROOM}Mi") },
            "requests.ephemeral-storage" to disk,
            "limits.ephemeral-storage" to disk,
        )
        return ResourceQuotaBuilder()
            .withMetadata(meta("liftgate", r.namespace, r.environmentLabels()))
            .withNewSpec().withHard<String, Quantity>(hard.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()).endSpec()
            .build()
    }

    fun secret(r: Release): Secret = SecretBuilder()
        .withMetadata(meta(r.secretName(), r.namespace, r.deploymentLabels()))
        .withType("Opaque")
        .withImmutable(r.deployment.env != null)
        .withData<String, String>(r.envVars.associate { it.name to Base64.getEncoder().encodeToString(it.value.orEmpty().toByteArray()) })
        .build()

    fun deployment(r: Release, runtimeClass: String?, nodeSelector: Map<String, String> = emptyMap(), tolerations: List<Toleration> = emptyList()): KubeDeployment = DeploymentBuilder()
        .withMetadata(meta(r.service.slug, r.namespace, r.deploymentLabels()))
        .withNewSpec()
        .withReplicas(if (r.suspended) 0 else r.service.replicas)
        .withRevisionHistoryLimit(5)
        .withProgressDeadlineSeconds(600)
        .withMinReadySeconds(MIN_READY_SECONDS)
        .withSelector(r.selector())
        .withNewStrategy().withType("RollingUpdate").withNewRollingUpdate().withMaxSurge(IntOrString(1)).withMaxUnavailable(IntOrString(0)).endRollingUpdate().endStrategy()
        .withTemplate(podTemplate(r, runtimeClass, nodeSelector, tolerations, "Always"))
        .endSpec()
        .build()

    fun cronJob(r: Release, runtimeClass: String?, nodeSelector: Map<String, String> = emptyMap(), tolerations: List<Toleration> = emptyList()): CronJob = CronJobBuilder()
        .withMetadata(meta(r.service.slug, r.namespace, r.deploymentLabels()))
        .withNewSpec()
        .withSchedule(r.service.cronSchedule)
        .withSuspend(r.suspended)
        .withConcurrencyPolicy("Forbid")
        .withStartingDeadlineSeconds(CRON_START_DEADLINE_SECONDS)
        .withSuccessfulJobsHistoryLimit(3)
        .withFailedJobsHistoryLimit(3)
        .withNewJobTemplate().withNewSpec()
        .withBackoffLimit(2)
        .withActiveDeadlineSeconds(r.plan.cronTimeoutSeconds.toLong())
        .withTemplate(podTemplate(r, runtimeClass, nodeSelector, tolerations, "OnFailure"))
        .endSpec().endJobTemplate()
        .endSpec()
        .build()

    fun service(r: Release): KubeService = ServiceBuilder()
        .withMetadata(meta(r.service.slug, r.namespace, r.serviceLabels()))
        .withNewSpec()
        .withType("ClusterIP")
        .withSelector<String, String>(r.selectorLabels())
        .addNewPort().withName("http").withProtocol("TCP").withPort(SERVICE_PORT).withTargetPort(IntOrString(r.port())).endPort()
        .apply { r.port()?.takeIf { it != SERVICE_PORT }?.let { addNewPort().withName("app").withProtocol("TCP").withPort(it).withTargetPort(IntOrString(it)).endPort() } }
        .endSpec()
        .build()

    fun httpRoute(r: Release, gatewayNamespace: String, gatewayName: String): HTTPRoute = HTTPRouteBuilder()
        .withMetadata(meta(r.service.slug, r.namespace, r.serviceLabels()))
        .withNewSpec()
        .addNewParentRef().withGroup("gateway.networking.k8s.io").withKind("Gateway").withNamespace(gatewayNamespace).withName(gatewayName).endParentRef()
        .withHostnames(r.hostnames)
        .addNewRule().addNewBackendRef().withName(r.service.slug).withPort(SERVICE_PORT).endBackendRef().endRule()
        .endSpec()
        .build()

    fun networkPolicies(r: Release, gatewayNamespace: String, deniedCidrs: List<String> = emptyList()): List<NetworkPolicy> {
        val sameNamespace = NetworkPolicyPeerBuilder().withNewPodSelector().endPodSelector().build()
        val internet = NetworkPolicyPeerBuilder().withNewIpBlock().withCidr("0.0.0.0/0").withExcept(privateRanges + deniedCidrs).endIpBlock().build()
        return listOf(
            policy(r, "default-deny").build(),
            policy(r, "allow-internal").editSpec()
                .addNewIngress().withFrom(sameNamespace, namespacePeer(gatewayNamespace)).endIngress()
                .addNewEgress().withTo(sameNamespace).endEgress()
                .endSpec().build(),
            policy(r, "allow-egress").editSpec()
                .addNewEgress().withTo(namespacePeer("kube-system")).withPorts(port("UDP", 53), port("TCP", 53)).endEgress()
                .addNewEgress().withTo(internet).withPorts(tcpWithoutSmtp.map { (from, to) -> port("TCP", from, to) } + listOfNotNull(port("UDP", 1, MAX_PORT).takeIf { r.plan.udp })).endEgress()
                .endSpec().build(),
        )
    }

    fun logReaderBinding(r: Release, clusterRole: String, account: String, accountNamespace: String): RoleBinding = RoleBindingBuilder()
        .withMetadata(meta(clusterRole, r.namespace, r.environmentLabels()))
        .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("ClusterRole").withName(clusterRole).endRoleRef()
        .addNewSubject().withKind("ServiceAccount").withName(account).withNamespace(accountNamespace).endSubject()
        .build()

    fun gatewayListeners(domains: List<Domain>, gatewayNamespace: String, gatewayName: String): Gateway = GatewayBuilder()
        .withMetadata(meta(gatewayName, gatewayNamespace, emptyMap()))
        .withNewSpec().withListeners(domains.map { listener(it) }).endSpec()
        .build()

    fun certificate(domain: Domain, gatewayNamespace: String, issuer: String): GenericKubernetesResource = GenericKubernetesResourceBuilder()
        .withApiVersion("${certificateContext.group}/${certificateContext.version}")
        .withKind(certificateContext.kind)
        .withMetadata(meta(domain.hostname, gatewayNamespace, mapOf(MANAGED_LABEL to "true", SERVICE_ID_LABEL to domain.serviceId.toString())))
        .addToAdditionalProperties(
            "spec",
            mapOf(
                "secretName" to domain.tlsSecret(),
                "dnsNames" to listOf(domain.hostname),
                "issuerRef" to mapOf("name" to issuer, "kind" to "ClusterIssuer", "group" to "cert-manager.io"),
            ),
        )
        .build()

    private fun listener(domain: Domain): Listener = ListenerBuilder()
        .withName("$DOMAIN_LISTENER${domain.id}")
        .withHostname(domain.hostname)
        .withPort(HTTPS_PORT)
        .withProtocol("HTTPS")
        .withNewTls().withMode("Terminate").addNewCertificateRef().withKind("Secret").withName(domain.tlsSecret()).endCertificateRef().endTls()
        .withNewAllowedRoutes().withNewNamespaces().withFrom("All").endNamespaces().endAllowedRoutes()
        .build()

    private fun Domain.tlsSecret() = "$hostname-tls"

    private fun podTemplate(r: Release, runtimeClass: String?, nodeSelector: Map<String, String>, tolerations: List<Toleration>, restartPolicy: String): PodTemplateSpec = PodTemplateSpecBuilder()
        .withMetadata(meta(null, null, r.deploymentLabels()))
        .editMetadata().withAnnotations<String, String>(r.plan.egressBandwidth?.let { mapOf("kubernetes.io/egress-bandwidth" to it) }).endMetadata()
        .withNewSpec()
        .withRuntimeClassName(runtimeClass)
        .withNodeSelector<String, String>(nodeSelector)
        .withTolerations(tolerations)
        .withRestartPolicy(restartPolicy)
        .withTerminationGracePeriodSeconds(GRACE_SECONDS)
        .withAutomountServiceAccountToken(false)
        .withEnableServiceLinks(false)
        .withNewSecurityContext()
        .withRunAsNonRoot(true).withRunAsUser(TENANT_UID).withRunAsGroup(TENANT_UID).withFsGroup(TENANT_UID)
        .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
        .endSecurityContext()
        .withContainers(container(r))
        .endSpec()
        .build()

    private fun container(r: Release): Container {
        val limits = mapOf("cpu" to Quantity("${r.service.cpuMillis}m"), "memory" to Quantity("${r.service.memoryMb}Mi"), "ephemeral-storage" to Quantity("${r.plan.ephemeralMb}Mi"))
        val requests = limits + ("cpu" to Quantity("${(r.service.cpuMillis * r.plan.cpuRequestRatio).roundToInt().coerceAtLeast(1)}m"))
        return ContainerBuilder()
            .withName("app")
            .withImage(r.build.imageRef)
            .withCommand(r.service.startCommand?.let { listOf("/bin/sh", "-c", it) })
            .addNewEnvFrom().withNewSecretRef().withName(r.secretName()).endSecretRef().endEnvFrom()
            .withEnv(listOfNotNull(r.port()?.let { KubeEnvVar("PORT", it.toString(), null) }))
            .withPorts(listOfNotNull(r.port()?.let { ContainerPortBuilder().withName("http").withContainerPort(it).build() }))
            .withReadinessProbe(r.probe(2, 3, r.service.healthCheckPath))
            .withStartupProbe(r.service.healthCheckPath?.let { r.probe(5, 60, it) })
            .withLivenessProbe(r.service.healthCheckPath?.let { r.probe(10, 6, it) })
            .withNewLifecycle().withNewPreStop().withNewSleep(PRE_STOP_SECONDS).endPreStop().endLifecycle()
            .withResources(ResourceRequirementsBuilder().withRequests<String, Quantity>(requests).withLimits<String, Quantity>(limits).build())
            .withNewSecurityContext()
            .withAllowPrivilegeEscalation(false)
            .withNewCapabilities().withDrop("ALL").endCapabilities()
            .endSecurityContext()
            .build()
    }

    private fun Release.probe(period: Int, failures: Int, path: String?): Probe? = port()?.let {
        val probe = ProbeBuilder().withPeriodSeconds(period).withFailureThreshold(failures)
        if (path != null) probe.withNewHttpGet().withPath(path).withPort(IntOrString(it)).endHttpGet().build()
        else probe.withNewTcpSocket().withPort(IntOrString(it)).endTcpSocket().build()
    }

    private fun Release.port() = service.port ?: DEFAULT_WEB_PORT.takeIf { service.kind.servesHttp }

    private fun Release.secretName() = if (deployment.env == null) "${service.slug}-env" else "${service.slug}-env-${deployment.id.toString().take(8)}"

    private fun Release.selectorLabels() = mapOf(SERVICE_LABEL to service.slug)

    private fun Release.selector(): LabelSelector = LabelSelectorBuilder().withMatchLabels<String, String>(selectorLabels()).build()

    private fun Release.environmentLabels() = mapOf(
        MANAGED_LABEL to "true",
        "liftgate.dev/org" to org.slug,
        ORG_ID_LABEL to org.id.toString(),
        "liftgate.dev/project" to project.slug,
        "liftgate.dev/environment" to environment.slug,
    )

    private fun Release.serviceLabels() = environmentLabels() + mapOf(SERVICE_LABEL to service.slug, SERVICE_ID_LABEL to service.id.toString())

    private fun Release.deploymentLabels() = serviceLabels() + (DEPLOYMENT_LABEL to deployment.id.toString())

    private fun meta(name: String?, namespace: String?, labels: Map<String, String>): ObjectMeta =
        ObjectMetaBuilder().withName(name).withNamespace(namespace).withLabels<String, String>(labels).build()

    private fun policy(r: Release, name: String) = NetworkPolicyBuilder()
        .withMetadata(meta(name, r.namespace, r.environmentLabels()))
        .withNewSpec().withNewPodSelector().endPodSelector().withPolicyTypes("Ingress", "Egress").endSpec()

    private fun namespacePeer(namespace: String): NetworkPolicyPeer = NetworkPolicyPeerBuilder()
        .withNewNamespaceSelector().addToMatchLabels("kubernetes.io/metadata.name", namespace).endNamespaceSelector()
        .build()

    private fun port(protocol: String, from: Int, to: Int? = null): NetworkPolicyPort =
        NetworkPolicyPortBuilder().withProtocol(protocol).withPort(IntOrString(from)).withEndPort(to).build()
}
