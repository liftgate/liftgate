package dev.liftgate.k8s

import dev.liftgate.org.Plan
import dev.liftgate.service.ServiceKind
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.TolerationBuilder
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class ResourcesTest {
    private val release = testRelease()
    private val free = Plan(replicas = 6, cpuMillis = 1000, memoryMb = 1024, cpuRequestRatio = 0.25, ephemeralMb = 1024, egressBandwidth = "20M", udp = false)
    private val environmentLabels = mapOf(
        "liftgate.dev/managed" to "true",
        "liftgate.dev/org" to "acme",
        "liftgate.dev/org-id" to testOrg.id.toString(),
        "liftgate.dev/project" to "shop",
        "liftgate.dev/environment" to "production",
    )
    private val serviceLabels = environmentLabels + mapOf("liftgate.dev/service" to "api", "liftgate.dev/service-id" to testService.id.toString())
    private val deploymentLabels = serviceLabels + ("liftgate.dev/deployment" to testDeployment.id.toString())

    @Test
    fun `namespace is named after the environment and enforces the restricted profile`() {
        val namespace = Resources.namespace(release)
        assertEquals("env-" + testEnvironment.id.toString().replace("-", "").take(12), namespace.metadata.name)
        assertEquals(environmentLabels + ("pod-security.kubernetes.io/enforce" to "restricted"), namespace.metadata.labels)
    }

    @Test
    fun `secret holds every env var base64 encoded`() {
        val secret = Resources.secret(release)
        assertEquals("api-env", secret.metadata.name)
        assertEquals(release.namespace, secret.metadata.namespace)
        assertEquals(serviceLabels, secret.metadata.labels)
        assertEquals(mapOf("DATABASE_URL" to "postgres://db", "MODE" to "production"), secret.data.mapValues { String(Base64.getDecoder().decode(it.value)) })
    }

    @Test
    fun `web deployment follows the release rules`() {
        val deployment = Resources.deployment(release, "gvisor")
        assertEquals("api", deployment.metadata.name)
        assertEquals(release.namespace, deployment.metadata.namespace)
        assertEquals(deploymentLabels, deployment.metadata.labels)
        assertEquals(2, deployment.spec.replicas)
        assertEquals(5, deployment.spec.revisionHistoryLimit)
        assertEquals(600, deployment.spec.progressDeadlineSeconds)
        assertEquals("RollingUpdate", deployment.spec.strategy.type)
        assertEquals(mapOf("liftgate.dev/service" to "api"), deployment.spec.selector.matchLabels)
        assertEquals(deploymentLabels, deployment.spec.template.metadata.labels)
        assertRestricted(deployment.spec.template.spec)
        assertEquals("gvisor", deployment.spec.template.spec.runtimeClassName)
        assertEquals("Always", deployment.spec.template.spec.restartPolicy)

        val container = deployment.spec.template.spec.containers.single()
        assertEquals(testBuild.imageRef, container.image)
        assertEquals("api-env", container.envFrom.single().secretRef.name)
        assertEquals("3000", container.env.single { it.name == "PORT" }.value)
        assertEquals(3000, container.ports.single().containerPort)
        assertEquals("/", container.readinessProbe.httpGet.path)
        assertEquals(3000, container.readinessProbe.httpGet.port.intVal)
        assertNull(container.readinessProbe.tcpSocket)
        assertEquals(mapOf("cpu" to Quantity("250m"), "memory" to Quantity("256Mi"), "ephemeral-storage" to Quantity("2Gi")), container.resources.requests)
        assertEquals(container.resources.requests, container.resources.limits)
        assertTrue(container.command.isNullOrEmpty())
    }

    @Test
    fun `web service without a port listens on 8080 behind a tcp probe`() {
        val container = Resources.deployment(testRelease(testService.copy(port = null)), null).spec.template.spec.containers.single()
        assertEquals(8080, container.ports.single().containerPort)
        assertEquals("8080", container.env.single().value)
        assertEquals(8080, container.readinessProbe.tcpSocket.port.intVal)
        assertNull(container.readinessProbe.httpGet)
    }

    @Test
    fun `worker has no port, probe or runtime class and runs its start command`() {
        val worker = testService.copy(kind = ServiceKind.WORKER, port = null, startCommand = "node worker.js")
        val spec = Resources.deployment(testRelease(worker), null).spec.template.spec
        val container = spec.containers.single()
        assertNull(spec.runtimeClassName)
        assertTrue(container.ports.isEmpty())
        assertTrue(container.env.isEmpty())
        assertNull(container.readinessProbe)
        assertEquals(listOf("/bin/sh", "-c", "node worker.js"), container.command)
    }

    @Test
    fun `cron job runs the same restricted pod on the schedule`() {
        val cron = Resources.cronJob(testRelease(testService.copy(kind = ServiceKind.CRON, port = null, cronSchedule = "*/5 * * * *")), "gvisor")
        val pod = cron.spec.jobTemplate.spec.template
        assertEquals("api", cron.metadata.name)
        assertEquals("*/5 * * * *", cron.spec.schedule)
        assertEquals("Forbid", cron.spec.concurrencyPolicy)
        assertEquals(deploymentLabels, pod.metadata.labels)
        assertEquals("OnFailure", pod.spec.restartPolicy)
        assertEquals("gvisor", pod.spec.runtimeClassName)
        assertRestricted(pod.spec)
    }

    @Test
    fun `node selector and tolerations place the deployment and the cron job on the workload pool`() {
        val selector = mapOf("liftgate.dev/pool" to "workloads")
        val tolerations = listOf(TolerationBuilder().withKey("liftgate.dev/pool").withOperator("Equal").withValue("workloads").withEffect("NoSchedule").build())
        val unpinned = Resources.deployment(release, null).spec.template.spec
        assertTrue(unpinned.nodeSelector.isNullOrEmpty() && unpinned.tolerations.isNullOrEmpty())
        listOf(
            Resources.deployment(release, "gvisor", selector, tolerations).spec.template.spec,
            Resources.cronJob(release, "gvisor", selector, tolerations).spec.jobTemplate.spec.template.spec,
        ).forEach {
            assertEquals(selector, it.nodeSelector)
            assertEquals(tolerations, it.tolerations)
            assertEquals("gvisor", it.runtimeClassName)
        }
    }

    @Test
    fun `service and route expose the web port on every verified hostname`() {
        val service = Resources.service(release)
        val port = service.spec.ports.single()
        assertEquals("ClusterIP", service.spec.type)
        assertEquals(mapOf("liftgate.dev/service" to "api"), service.spec.selector)
        assertEquals(80, port.port)
        assertEquals(3000, port.targetPort.intVal)

        val route = Resources.httpRoute(release, "liftgate-system", "liftgate")
        val parent = route.spec.parentRefs.single()
        val backend = route.spec.rules.single().backendRefs.single()
        assertEquals("api", route.metadata.name)
        assertEquals(serviceLabels, route.metadata.labels)
        assertEquals(listOf("liftgate-system", "liftgate", "Gateway"), listOf(parent.namespace, parent.name, parent.kind))
        assertEquals(listOf("api-shop-acme.liftgate.app", "api.acme.dev"), route.spec.hostnames)
        assertEquals("api" to 80, backend.name to backend.port)
    }

    @Test
    fun `only web and static services with a verified hostname are routable`() {
        assertTrue(release.routable)
        assertTrue(testRelease(testService.copy(kind = ServiceKind.STATIC)).routable)
        assertFalse(release.copy(domains = emptyList()).routable)
        assertFalse(testRelease(testService.copy(kind = ServiceKind.WORKER)).routable)
    }

    @Test
    fun `static service without a port gets PORT 8080, a service and a route`() {
        val static = testRelease(testService.copy(kind = ServiceKind.STATIC, port = null))
        assertEquals("8080", Resources.deployment(static, null).spec.template.spec.containers.single().env.single { it.name == "PORT" }.value)
        assertEquals(8080, Resources.service(static).spec.ports.single().targetPort.intVal)
        assertEquals(listOf("api-shop-acme.liftgate.app", "api.acme.dev"), Resources.httpRoute(static, "liftgate-system", "liftgate").spec.hostnames)
    }

    @Test
    fun `network policies deny by default and open the namespace, the gateway, dns and the internet without smtp`() {
        val policies = Resources.networkPolicies(release, "liftgate-system").associateBy { it.metadata.name }
        assertEquals(setOf("default-deny", "allow-internal", "allow-egress"), policies.keys)
        policies.values.forEach {
            assertEquals(release.namespace, it.metadata.namespace)
            assertEquals(environmentLabels, it.metadata.labels)
            assertTrue(it.spec.podSelector.matchLabels.isNullOrEmpty())
            assertEquals(listOf("Ingress", "Egress"), it.spec.policyTypes)
        }

        val deny = policies.getValue("default-deny").spec
        assertTrue(deny.ingress.isEmpty() && deny.egress.isEmpty())

        val internal = policies.getValue("allow-internal").spec
        val (sameNamespace, gateway) = internal.ingress.single().from
        assertTrue(sameNamespace.podSelector.matchLabels.isNullOrEmpty())
        assertNull(sameNamespace.namespaceSelector)
        assertEquals(mapOf("kubernetes.io/metadata.name" to "liftgate-system"), gateway.namespaceSelector.matchLabels)
        assertEquals(sameNamespace, internal.egress.single().to.single())

        val (dns, internet) = policies.getValue("allow-egress").spec.egress
        assertEquals(mapOf("kubernetes.io/metadata.name" to "kube-system"), dns.to.single().namespaceSelector.matchLabels)
        assertEquals(setOf("UDP" to 53, "TCP" to 53), dns.ports.map { it.protocol to it.port.intVal }.toSet())

        val block = internet.to.single().ipBlock
        assertEquals("0.0.0.0/0", block.cidr)
        assertTrue(block.except.containsAll(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16")))
        val tcp = internet.ports.filter { it.protocol == "TCP" }.map { it.port.intVal..it.endPort }
        listOf(25, 465, 587, 2525).forEach { smtp -> assertTrue(tcp.none { smtp in it }, "port $smtp must stay closed") }
        listOf(1, 22, 80, 443, 5432, 65535).forEach { open -> assertTrue(tcp.any { open in it }, "port $open must be open") }
    }

    @Test
    fun `certificate requests the custom hostname from the letsencrypt issuer beside the gateway`() {
        val certificate = Resources.certificate(release.domains.last(), "liftgate-system")
        val spec = certificate.additionalProperties.getValue("spec") as Map<*, *>
        assertEquals("cert-manager.io/v1" to "Certificate", certificate.apiVersion to certificate.kind)
        assertEquals("api.acme.dev", certificate.metadata.name)
        assertEquals("liftgate-system", certificate.metadata.namespace)
        assertEquals(mapOf(MANAGED_LABEL to "true", SERVICE_ID_LABEL to testService.id.toString()), certificate.metadata.labels)
        assertEquals(listOf("api.acme.dev"), spec["dnsNames"])
        assertEquals("api.acme.dev-tls", spec["secretName"])
        assertEquals(mapOf("name" to "letsencrypt", "kind" to "ClusterIssuer", "group" to "cert-manager.io"), spec["issuerRef"])
    }

    @Test
    fun `gateway listeners terminate tls for each custom hostname with its certificate`() {
        val domain = release.domains.last()
        val gateway = Resources.gatewayListeners(listOf(domain), "liftgate-system", "liftgate")
        val listener = gateway.spec.listeners.single()
        assertEquals("liftgate-system" to "liftgate", gateway.metadata.namespace to gateway.metadata.name)
        assertEquals(listOf("domain-${domain.id}", "api.acme.dev", "HTTPS", 443), listOf(listener.name, listener.hostname, listener.protocol, listener.port))
        assertEquals("Terminate" to "api.acme.dev-tls", listener.tls.mode to listener.tls.certificateRefs.single().name)
        assertEquals("All", listener.allowedRoutes.namespaces.from)
        assertNull(gateway.spec.gatewayClassName)
    }

    @Test
    fun `resource quota is twice the plan's budgets so a rollout can surge, disk included`() {
        val quota = Resources.resourceQuota(release.copy(plan = free))
        assertEquals(release.namespace, quota.metadata.namespace)
        assertEquals(
            mapOf(
                "pods" to Quantity("12"), "limits.cpu" to Quantity("2"), "limits.memory" to Quantity("2Gi"),
                "requests.ephemeral-storage" to Quantity("12Gi"), "limits.ephemeral-storage" to Quantity("12Gi"),
            ),
            quota.spec.hard,
        )
        assertTrue(Resources.resourceQuota(release).spec.hard.isNullOrEmpty())
    }

    @Test
    fun `free plan egress allows udp only for dns while tcp stays open, and every plan blocks smtp`() {
        fun egress(plan: Plan) = Resources.networkPolicies(release.copy(plan = plan), "liftgate-system").single { it.metadata.name == "allow-egress" }.spec.egress
        val (dns, internet) = egress(free)
        assertEquals(setOf("UDP" to 53, "TCP" to 53), dns.ports.map { it.protocol to it.port.intVal }.toSet())
        assertEquals(setOf("TCP"), internet.ports.map { it.protocol }.toSet())
        assertEquals(egress(Plan())[1].ports.filter { it.protocol == "TCP" }, internet.ports)
        assertEquals(listOf(1 to 65535), egress(Plan())[1].ports.filter { it.protocol == "UDP" }.map { it.port.intVal to it.endPort })
        val tcp = internet.ports.map { it.port.intVal..it.endPort }
        listOf(25, 465, 587, 2525).forEach { smtp -> assertTrue(tcp.none { smtp in it }, "port $smtp must stay closed") }
        listOf(22, 443, 3306, 5432, 6379, 27017).forEach { open -> assertTrue(tcp.any { open in it }, "port $open must be open") }
    }

    @Test
    fun `free plan pods request a quarter of their cpu, cap their disk and carry the egress bandwidth`() {
        val limited = release.copy(plan = free)
        val container = Resources.deployment(limited, null).spec.template.spec.containers.single()
        assertEquals(mapOf("cpu" to Quantity("63m"), "memory" to Quantity("256Mi"), "ephemeral-storage" to Quantity("1Gi")), container.resources.requests)
        assertEquals(mapOf("cpu" to Quantity("250m"), "memory" to Quantity("256Mi"), "ephemeral-storage" to Quantity("1Gi")), container.resources.limits)
        val bandwidth = mapOf("kubernetes.io/egress-bandwidth" to "20M")
        assertEquals(bandwidth, Resources.deployment(limited, null).spec.template.metadata.annotations)
        assertEquals(bandwidth, Resources.cronJob(limited, null).spec.jobTemplate.spec.template.metadata.annotations)
        assertTrue(Resources.deployment(release, null).spec.template.metadata.annotations.isNullOrEmpty())
    }

    @Test
    fun `a suspended release renders no replicas, a suspended cron job and no routing`() {
        val suspended = release.copy(org = testOrg.copy(suspendedAt = Instant.now()))
        assertEquals(0, Resources.deployment(suspended, null).spec.replicas)
        assertEquals(true, Resources.cronJob(suspended, null).spec.suspend)
        assertFalse(suspended.routable)
        assertEquals(false, Resources.cronJob(release, null).spec.suspend)
    }

    private fun assertRestricted(pod: PodSpec) {
        val container = pod.containers.single().securityContext
        assertEquals(true, pod.securityContext.runAsNonRoot)
        assertEquals("RuntimeDefault", pod.securityContext.seccompProfile.type)
        assertEquals(false, pod.automountServiceAccountToken)
        assertEquals(false, container.allowPrivilegeEscalation)
        assertEquals(listOf("ALL"), container.capabilities.drop)
    }
}
