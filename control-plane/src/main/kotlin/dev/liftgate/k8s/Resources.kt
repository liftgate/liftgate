package dev.liftgate.k8s

import dev.liftgate.config.DatabaseBackupConfig
import dev.liftgate.database.DatabaseScope
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
import io.fabric8.kubernetes.api.model.EnvVarBuilder
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
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
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
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategyBuilder
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
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRuleBuilder
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
const val DATABASE_ID_LABEL = "liftgate.dev/database-id"
const val BACKUP_STORE = "liftgate-backup"
private const val BARMAN = "barman-cloud.cloudnative-pg.io"
const val DOMAIN_LISTENER = "domain-"
val HasMetadata.deploymentId get() = metadata.labels?.get(DEPLOYMENT_LABEL)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
val certificateContext = crd("cert-manager.io", "Certificate", "certificates")
val clusterContext = crd("postgresql.cnpg.io", "Cluster", "clusters")
val scheduledBackupContext = crd("postgresql.cnpg.io", "ScheduledBackup", "scheduledbackups")
val backupContext = crd("postgresql.cnpg.io", "Backup", "backups")
val objectStoreContext = crd("barmancloud.cnpg.io", "ObjectStore", "objectstores")

private fun crd(group: String, kind: String, plural: String): ResourceDefinitionContext =
    ResourceDefinitionContext.Builder().withGroup(group).withVersion("v1").withKind(kind).withPlural(plural).withNamespaced(true).build()

/**
 * @author Dean
 * @date 9/17/2026
 */
data class Release(
    val deployment: Deployment,
    val build: Build,
    val service: Service,
    override val environment: Environment,
    override val project: Project,
    override val org: Organization,
    val envVars: List<EnvVar>,
    val domains: List<Domain>,
    override val plan: Plan = Plan(),
    val links: Map<String, String> = emptyMap(),
) : Tenancy {
    val hostnames get() = domains.map { it.hostname }
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
    private const val POSTGRES_PORT = 5432
    private const val INSTANCE_STATUS_PORT = 8000
    const val SIDECAR_CPU_MILLIS = 200
    const val SIDECAR_MEMORY_MB = 512
    private val sidecarRequests = mapOf("cpu" to "50m", "memory" to "256Mi", "ephemeral-storage" to "256Mi")
    private val sidecarLimits = sidecarRequests + mapOf("cpu" to "${SIDECAR_CPU_MILLIS}m", "memory" to "${SIDECAR_MEMORY_MB}Mi")
    val privateRanges = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16")
    private val tcpWithoutSmtp = listOf(1 to 24, 26 to 464, 466 to 586, 588 to 2524, 2526 to MAX_PORT)

    fun namespace(r: Tenancy): Namespace = NamespaceBuilder()
        .withMetadata(meta(r.namespace, null, r.environmentLabels() + ("pod-security.kubernetes.io/enforce" to "restricted")))
        .build()

    fun resourceQuota(r: Tenancy): ResourceQuota {
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
        .withStrategy(
            if (r.service.volume != null) DeploymentStrategyBuilder().withType("Recreate").build()
            else DeploymentStrategyBuilder().withType("RollingUpdate").withNewRollingUpdate().withMaxSurge(IntOrString(1)).withMaxUnavailable(IntOrString(0)).endRollingUpdate().build(),
        )
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

    fun networkPolicies(r: Tenancy, gatewayNamespace: String, deniedCidrs: List<String> = emptyList()): List<NetworkPolicy> {
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

    fun readerBinding(r: Tenancy, clusterRole: String, account: String, accountNamespace: String): RoleBinding = RoleBindingBuilder()
        .withMetadata(meta(clusterRole, r.namespace, r.environmentLabels()))
        .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("ClusterRole").withName(clusterRole).endRoleRef()
        .addNewSubject().withKind("ServiceAccount").withName(account).withNamespace(accountNamespace).endSubject()
        .build()

    fun gatewayListeners(domains: List<Domain>, gatewayNamespace: String, gatewayName: String): Gateway = GatewayBuilder()
        .withMetadata(meta(gatewayName, gatewayNamespace, emptyMap()))
        .withNewSpec().withListeners(domains.map { listener(it) }).endSpec()
        .build()

    fun certificate(domain: Domain, gatewayNamespace: String, issuer: String) = generic(
        certificateContext,
        meta(domain.hostname, gatewayNamespace, mapOf(MANAGED_LABEL to "true", SERVICE_ID_LABEL to domain.serviceId.toString())),
        mapOf(
            "secretName" to domain.tlsSecret(),
            "dnsNames" to listOf(domain.hostname),
            "issuerRef" to mapOf("name" to issuer, "kind" to "ClusterIssuer", "group" to "cert-manager.io"),
        ),
    )

    fun volumeClaim(r: Release, storageClass: String?): PersistentVolumeClaim? = r.service.volume?.let {
        PersistentVolumeClaimBuilder()
            .withMetadata(meta(r.volumeName(), r.namespace, r.serviceLabels()))
            .withNewSpec()
            .withAccessModes("ReadWriteOnce")
            .withStorageClassName(storageClass)
            .withNewResources().withRequests<String, Quantity>(mapOf("storage" to Quantity("${it.sizeGb}Gi"))).endResources()
            .endSpec()
            .build()
    }

    fun cluster(d: DatabaseScope, backup: DatabaseBackupConfig?, storageClass: String?, nodeSelector: Map<String, String> = emptyMap(), tolerations: List<Toleration> = emptyList()): GenericKubernetesResource {
        val database = d.database
        val limits = mapOf("cpu" to "${database.cpuMillis}m", "memory" to "${database.memoryMb}Mi", "ephemeral-storage" to "${d.plan.ephemeralMb}Mi")
        val requests = limits + ("cpu" to "${(database.cpuMillis * d.plan.cpuRequestRatio).roundToInt().coerceAtLeast(1)}m")
        val recovery = mapOf("source" to "origin") + listOfNotNull(database.restoreTarget?.let { "recoveryTarget" to mapOf("targetTime" to it.toString()) })
        return generic(
            clusterContext,
            meta(database.slug, d.namespace, d.databaseLabels()),
            mapOf(
                "instances" to 1,
                "storage" to mapOf("size" to "${database.storageGb}Gi", "storageClass" to storageClass).filterValues { it != null },
                "resources" to mapOf("requests" to requests, "limits" to limits),
                "affinity" to mapOf("nodeSelector" to nodeSelector, "tolerations" to tolerations),
                "inheritedMetadata" to mapOf("labels" to d.databaseLabels()),
                "bootstrap" to if (database.restoredFrom == null) mapOf("initdb" to mapOf("database" to "app", "owner" to "app")) else mapOf("recovery" to recovery),
                "externalClusters" to database.restoredFrom?.let { listOf(mapOf("name" to "origin", "plugin" to barman(it))) },
                "plugins" to backup?.let { listOf(barman(database.id) + ("isWALArchiver" to true)) },
            ).filterValues { it != null },
        )
    }

    fun scheduledBackup(d: DatabaseScope, backup: DatabaseBackupConfig) = generic(
        scheduledBackupContext,
        meta(d.database.slug, d.namespace, d.databaseLabels()),
        mapOf(
            "schedule" to backup.schedule,
            "immediate" to true,
            "backupOwnerReference" to "cluster",
            "cluster" to mapOf("name" to d.database.slug),
            "method" to "plugin",
            "pluginConfiguration" to mapOf("name" to BARMAN),
        ),
    )

    fun objectStore(r: Tenancy, backup: DatabaseBackupConfig): GenericKubernetesResource {
        fun key(name: String) = mapOf("name" to BACKUP_STORE, "key" to name)
        return generic(
            objectStoreContext,
            meta(BACKUP_STORE, r.namespace, r.environmentLabels()),
            mapOf(
                "retentionPolicy" to backup.retention,
                "configuration" to mapOf(
                    "destinationPath" to "${backup.destinationPath.trimEnd('/')}/${r.namespace}",
                    "endpointURL" to backup.endpointUrl,
                    "s3Credentials" to mapOf("accessKeyId" to key("ACCESS_KEY_ID"), "secretAccessKey" to key("ACCESS_SECRET_KEY"), "region" to key("ACCESS_REGION")),
                    "wal" to mapOf("compression" to "gzip"),
                ).filterValues { it != null },
                "instanceSidecarConfiguration" to mapOf("resources" to mapOf("requests" to sidecarRequests, "limits" to sidecarLimits)),
            ),
        )
    }

    fun backupSecret(r: Tenancy, backup: DatabaseBackupConfig): Secret = SecretBuilder()
        .withMetadata(meta(BACKUP_STORE, r.namespace, r.environmentLabels()))
        .withType("Opaque")
        .withData<String, String>(
            mapOf("ACCESS_KEY_ID" to backup.accessKeyId.orEmpty(), "ACCESS_SECRET_KEY" to backup.secretAccessKey.orEmpty(), "ACCESS_REGION" to backup.region)
                .mapValues { Base64.getEncoder().encodeToString(it.value.toByteArray()) },
        )
        .build()

    fun databasePolicy(r: Tenancy, egress: List<Pair<String, Int>>): NetworkPolicy = NetworkPolicyBuilder()
        .withMetadata(meta("allow-databases", r.namespace, r.environmentLabels()))
        .withNewSpec()
        .withNewPodSelector().addNewMatchExpression().withKey(DATABASE_ID_LABEL).withOperator("Exists").endMatchExpression().endPodSelector()
        .withPolicyTypes("Ingress", "Egress")
        .addNewIngress()
        .addNewFrom().withNewNamespaceSelector().endNamespaceSelector().withNewPodSelector().addToMatchLabels("app.kubernetes.io/name", "cloudnative-pg").endPodSelector().endFrom()
        .withPorts(port("TCP", POSTGRES_PORT), port("TCP", INSTANCE_STATUS_PORT))
        .endIngress()
        .withEgress(egress.map { (cidr, to) -> NetworkPolicyEgressRuleBuilder().addNewTo().withNewIpBlock().withCidr(cidr).endIpBlock().endTo().withPorts(port("TCP", to)).build() })
        .endSpec()
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
        .withVolumes(listOfNotNull(r.service.volume?.let { VolumeBuilder().withName("data").withNewPersistentVolumeClaim().withClaimName(r.volumeName()).endPersistentVolumeClaim().build() }))
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
            .withImage(r.build.imageRef?.let { ref -> r.build.imageDigest?.let { "${ref.substringBeforeLast(':')}@$it" } ?: ref })
            .withImagePullPolicy("Always".takeIf { r.build.imageDigest == null })
            .withCommand(r.service.startCommand?.let { listOf("/bin/sh", "-c", it) })
            .addNewEnvFrom().withNewSecretRef().withName(r.secretName()).endSecretRef().endEnvFrom()
            .withEnv(
                listOfNotNull(r.port()?.let { KubeEnvVar("PORT", it.toString(), null) }) + r.links.toSortedMap().map { (name, database) ->
                    EnvVarBuilder().withName(name).withNewValueFrom().withNewSecretKeyRef().withName("$database-app").withKey("uri").endSecretKeyRef().endValueFrom().build()
                },
            )
            .withVolumeMounts(listOfNotNull(r.service.volume?.let { VolumeMountBuilder().withName("data").withMountPath(it.mountPath).build() }))
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

    private fun Release.volumeName() = "${service.slug}-data"

    private fun Release.secretName() = if (deployment.env == null) "${service.slug}-env" else "${service.slug}-env-${deployment.id.toString().take(8)}"

    private fun Release.selectorLabels() = mapOf(SERVICE_LABEL to service.slug)

    private fun Release.selector(): LabelSelector = LabelSelectorBuilder().withMatchLabels<String, String>(selectorLabels()).build()

    private fun Tenancy.environmentLabels() = mapOf(
        MANAGED_LABEL to "true",
        "liftgate.dev/org" to org.slug,
        ORG_ID_LABEL to org.id.toString(),
        "liftgate.dev/project" to project.slug,
        "liftgate.dev/environment" to environment.slug,
    )

    private fun Release.serviceLabels() = environmentLabels() + mapOf(SERVICE_LABEL to service.slug, SERVICE_ID_LABEL to service.id.toString())

    private fun Release.deploymentLabels() = serviceLabels() + (DEPLOYMENT_LABEL to deployment.id.toString())

    private fun DatabaseScope.databaseLabels() = environmentLabels() + (DATABASE_ID_LABEL to database.id.toString())

    private fun barman(serverName: UUID) = mapOf("name" to BARMAN, "parameters" to mapOf("barmanObjectName" to BACKUP_STORE, "serverName" to serverName.toString()))

    private fun generic(context: ResourceDefinitionContext, metadata: ObjectMeta, spec: Map<String, Any?>): GenericKubernetesResource = GenericKubernetesResourceBuilder()
        .withApiVersion("${context.group}/${context.version}")
        .withKind(context.kind)
        .withMetadata(metadata)
        .addToAdditionalProperties("spec", spec)
        .build()

    private fun meta(name: String?, namespace: String?, labels: Map<String, String>): ObjectMeta =
        ObjectMetaBuilder().withName(name).withNamespace(namespace).withLabels<String, String>(labels).build()

    private fun policy(r: Tenancy, name: String) = NetworkPolicyBuilder()
        .withMetadata(meta(name, r.namespace, r.environmentLabels()))
        .withNewSpec().withNewPodSelector().endPodSelector().withPolicyTypes("Ingress", "Egress").endSpec()

    private fun namespacePeer(namespace: String): NetworkPolicyPeer = NetworkPolicyPeerBuilder()
        .withNewNamespaceSelector().addToMatchLabels("kubernetes.io/metadata.name", namespace).endNamespaceSelector()
        .build()

    private fun port(protocol: String, from: Int, to: Int? = null): NetworkPolicyPort =
        NetworkPolicyPortBuilder().withProtocol(protocol).withPort(IntOrString(from)).withEndPort(to).build()
}
