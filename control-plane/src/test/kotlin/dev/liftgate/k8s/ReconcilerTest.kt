package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.database.Database
import dev.liftgate.database.DatabaseScope
import dev.liftgate.database.Databases
import dev.liftgate.deploy.Builds
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.Deployments
import dev.liftgate.domain.DomainKind
import dev.liftgate.domain.Domains
import dev.liftgate.service.EnvVars
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.Volume
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBinding
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.fabric8.kubernetes.api.model.Service as KubeService
import io.fabric8.kubernetes.api.model.apps.Deployment as KubeDeployment

/**
 * @author Dean
 * @date 9/17/2026
 */
@EnableKubernetesMockClient
class ReconcilerTest {
    lateinit var server: KubernetesMockServer
    lateinit var client: KubernetesClient

    private val apply = "?fieldManager=liftgate&force=true"
    private val release = testRelease()
    private val custom = release.domains.last()
    private val namespaced = "namespaces/${release.namespace}"
    private val namespacePath = "/api/v1/$namespaced"
    private val secretPath = "/api/v1/$namespaced/secrets/api-env"
    private val servicePath = "/api/v1/$namespaced/services/api"
    private val deploymentPath = "/apis/apps/v1/$namespaced/deployments/api"
    private val cronJobPath = "/apis/batch/v1/$namespaced/cronjobs/api"
    private val routePath = "/apis/gateway.networking.k8s.io/v1/$namespaced/httproutes/api"
    private val quotaPath = "/api/v1/$namespaced/resourcequotas/liftgate"
    private val gatewayPath = "/apis/gateway.networking.k8s.io/v1/namespaces/liftgate-system/gateways/liftgate"
    private val certificatesPath = "/apis/cert-manager.io/v1/namespaces/liftgate-system/certificates"
    private val certificatePath = "$certificatesPath/api.acme.dev"
    private val policyPaths = listOf("default-deny", "allow-internal", "allow-egress").map { "/apis/networking.k8s.io/v1/$namespaced/networkpolicies/$it" }
    private val deployments = mockk<Deployments>(relaxUnitFun = true)
    private val services = mockk<Services>()
    private val databases = mockk<Databases> { coEvery { links(testService.id) } returns emptyMap() }
    private val app = mockk<App>().also {
        every { it.config } returns testConfig(mapOf("LIFTGATE_WORKLOAD_NODE_SELECTOR" to "liftgate.dev/pool=workloads", "LIFTGATE_WORKLOAD_TOLERATIONS" to "liftgate.dev/pool:NoSchedule"))
        every { it.deployments } returns deployments
        every { it.services } returns services
        every { it.databases } returns databases
        every { it.builds } returns mockk<Builds> { coEvery { byId(testBuild.id) } returns testBuild }
        every { it.envVars } returns mockk<EnvVars> { coEvery { list(testService.id, reveal = true) } returns release.envVars }
        every { it.domains } returns mockk<Domains> {
            coEvery { forService(testService.id) } returns release.domains + testDomain("pending.acme.dev", DomainKind.CUSTOM).copy(verifiedAt = null)
            coEvery { verifiedCustom() } returns listOf(custom)
        }
    }

    init {
        coEvery { deployments.byId(testDeployment.id) } returns testDeployment
        coEvery { deployments.forService(testService.id, 1) } returns listOf(testDeployment)
        coEvery { deployments.current(testService.id) } returns testDeployment
        serve(testService)
    }

    private fun serve(service: Service) = coEvery { services.scope(testService.id) } returns ServiceScope(service, testEnvironment, testProject, testOrg)

    private fun accept(path: String, resource: HasMetadata) = server.expect().patch().withPath(path + apply).andReturn(200, resource).always()

    private fun acceptAll(staleCertificate: String? = null, inPlace: Boolean = false) {
        val live = listOfNotNull(custom.takeIf { inPlace })
        val certificates = GenericKubernetesResourceList().apply {
            items = (live + listOfNotNull(staleCertificate).map { custom.copy(hostname = it) }).map { Resources.certificate(it, "liftgate-system", "letsencrypt") }
        }
        server.expect().get().withPath(gatewayPath).andReturn(200, Resources.gatewayListeners(live, "liftgate-system", "liftgate")).always()
        accept(gatewayPath, Resources.gatewayListeners(listOf(custom), "liftgate-system", "liftgate"))
        accept(namespacePath, Resources.namespace(release))
        accept(quotaPath, Resources.resourceQuota(release))
        accept(secretPath, Resources.secret(release))
        accept(servicePath, Resources.service(release))
        accept(deploymentPath, Resources.deployment(release, null))
        accept(cronJobPath, Resources.cronJob(release, null))
        accept(routePath, Resources.httpRoute(release, "liftgate-system", "liftgate"))
        accept(certificatePath, Resources.certificate(custom, "liftgate-system", "letsencrypt"))
        Resources.networkPolicies(release, "liftgate-system").zip(policyPaths).forEach { (policy, path) -> accept(path, policy) }
        server.expect().get().withPath("$certificatesPath?labelSelector=liftgate.dev%2Fmanaged%3Dtrue").andReturn(200, certificates).always()
    }

    private fun reject(code: Int) = server.expect().patch().withPath(namespacePath + apply)
        .andReturn(code, StatusBuilder().withCode(code).withMessage("the namespace was rejected").build()).always()

    private fun sent() = List(server.requestCount) { server.takeRequest() }

    private fun paths(method: String) = sent().filter { it.method == method }.map { it.path.removeSuffix(apply) }

    @Test
    fun `release server-side applies every object of a web service and marks it releasing`() = runBlocking {
        acceptAll()
        Reconciler(app, client).release(testDeployment.id)

        val applied = sent().filter { it.method == "PATCH" }
        assertEquals((listOf(namespacePath, quotaPath) + policyPaths + secretPath + deploymentPath + servicePath + routePath + certificatePath + gatewayPath).map { it + apply }, applied.map { it.path })
        assertTrue("\"hostname\":\"api.acme.dev\"" in applied.last().utf8Body)
        assertTrue(applied.all { it.getHeader("Content-Type").startsWith("application/apply-patch+yaml") })
        val pod = applied.single { it.path.startsWith(deploymentPath) }.utf8Body
        listOf("\"runtimeClassName\":\"gvisor\"", "\"nodeSelector\":{\"liftgate.dev/pool\":\"workloads\"}", "\"key\":\"liftgate.dev/pool\"").forEach { assertTrue(it in pod, it) }
        coVerify(exactly = 1) { deployments.transition(testDeployment.id, DeploymentStatus.RELEASING) }
        coVerify(exactly = 0) { deployments.transition(testDeployment.id, DeploymentStatus.RUNNING, any(), any()) }
        coVerify(exactly = 0) { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, any(), any()) }
    }

    @Test
    fun `release pins the pod to the digest its build recorded, and a build without one pulls its tag on every start`() = runBlocking {
        val digest = "sha256:" + "b".repeat(64)
        acceptAll()
        Reconciler(app, client).release(testDeployment.id)
        every { app.builds } returns mockk<Builds> { coEvery { byId(testBuild.id) } returns testBuild.copy(imageDigest = digest) }
        Reconciler(app, client).release(testDeployment.id)

        val (legacy, pinned) = sent().filter { it.method == "PATCH" && it.path.startsWith(deploymentPath) }.map { it.utf8Body }
        listOf("\"image\":\"${testBuild.imageRef}\"", "\"imagePullPolicy\":\"Always\"").forEach { assertTrue(it in legacy, it) }
        assertTrue("\"image\":\"registry.test/acme/shop-api@$digest\"" in pinned, pinned)
        assertTrue("imagePullPolicy" !in pinned, pinned)
    }

    @Test
    fun `release binds the log reader role, and not the database reader, to the api account inside the environment namespace`() = runBlocking {
        every { app.config } returns testConfig(
            mapOf("LIFTGATE_LOG_READER_ROLE" to "liftgate-log-reader", "LIFTGATE_DATABASE_READER_ROLE" to "liftgate-database-reader", "LIFTGATE_LOG_READER_ACCOUNT" to "liftgate-api"),
        )
        val bindingPath = "/apis/rbac.authorization.k8s.io/v1/$namespaced/rolebindings/liftgate-log-reader"
        acceptAll()
        accept(bindingPath, Resources.readerBinding(release, "liftgate-log-reader", "liftgate-api", client.namespace))
        Reconciler(app, client).release(testDeployment.id)

        val sent = sent()
        assertEquals(listOf(bindingPath + apply), sent.map { it.path }.filter { "/rolebindings/" in it })
        val binding = client.kubernetesSerialization.unmarshal(sent.single { it.path == bindingPath + apply }.utf8Body, RoleBinding::class.java)
        assertEquals(release.namespace, binding.metadata.namespace)
        assertEquals("ClusterRole" to "liftgate-log-reader", binding.roleRef.kind to binding.roleRef.name)
        assertEquals(listOf(Triple("ServiceAccount", client.namespace, "liftgate-api")), binding.subjects.map { Triple(it.kind, it.namespace, it.name) })
        coVerify(exactly = 0) { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, any(), any()) }
    }

    @Test
    fun `a rejected apply fails the deployment`() = runBlocking {
        reject(422)
        Reconciler(app, client).release(testDeployment.id)
        coVerify { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, 0, "the namespace was rejected") }
    }

    @Test
    fun `a server error on apply keeps the deployment releasing and fails the delivery so it is redelivered`() = runBlocking {
        reject(503)
        val noRetries = KubernetesClientBuilder().withConfig(ConfigBuilder(client.configuration).withRequestRetryBackoffLimit(0).build()).build()
        noRetries.use { assertEquals(503, assertFailsWith<KubernetesClientException> { Reconciler(app, it).release(testDeployment.id) }.code) }
        coVerify { deployments.transition(testDeployment.id, DeploymentStatus.RELEASING) }
        coVerify(exactly = 0) { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, any(), any()) }
    }

    @Test
    fun `a release retried after its workload rolled out still applies the route`() = runBlocking {
        server.expect().patch().withPath(routePath + apply).andReturn(503, StatusBuilder().withCode(503).withMessage("unavailable").build()).once()
        acceptAll()
        val noRetries = KubernetesClientBuilder().withConfig(ConfigBuilder(client.configuration).withRequestRetryBackoffLimit(0).build()).build()
        noRetries.use { assertEquals(503, assertFailsWith<KubernetesClientException> { Reconciler(app, it).release(testDeployment.id) }.code) }
        coEvery { deployments.byId(testDeployment.id) } returns testDeployment.copy(status = DeploymentStatus.RUNNING)
        Reconciler(app, client).release(testDeployment.id)
        assertEquals(listOf(routePath, routePath), paths("PATCH").filter { it == routePath })
        coVerify(exactly = 1) { deployments.transition(testDeployment.id, DeploymentStatus.RELEASING) }
        coVerify(exactly = 0) { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, any(), any()) }
    }

    @Test
    fun `a release is superseded without applying when the cluster already runs a newer deployment`() = runBlocking {
        val newer = testDeployment.copy(id = UUID.randomUUID(), createdAt = testDeployment.createdAt.plusSeconds(1))
        coEvery { deployments.byId(newer.id) } returns newer
        server.expect().get().withPath(deploymentPath).andReturn(200, Resources.deployment(release.copy(deployment = newer), null)).always()
        Reconciler(app, client).release(testDeployment.id)
        assertTrue(paths("PATCH").isEmpty())
        coVerify { deployments.transition(testDeployment.id, DeploymentStatus.SUPERSEDED) }
        coVerify(exactly = 0) { deployments.transition(testDeployment.id, DeploymentStatus.RELEASING) }
    }

    @Test
    fun `an apply that lands after a newer release re-applies the newer one and is superseded`() = runBlocking {
        acceptAll()
        val newer = testDeployment.copy(id = UUID.randomUUID(), status = DeploymentStatus.RUNNING, createdAt = testDeployment.createdAt.plusSeconds(1))
        coEvery { deployments.forService(testService.id, 1) } returnsMany listOf(listOf(testDeployment), listOf(newer))
        Reconciler(app, client).release(testDeployment.id)

        val labels = sent().filter { it.method == "PATCH" && it.path.startsWith(deploymentPath) }
            .map { client.kubernetesSerialization.unmarshal(it.utf8Body, KubeDeployment::class.java).metadata.labels[DEPLOYMENT_LABEL] }
        assertEquals(listOf(testDeployment.id, newer.id).map { it.toString() }, labels)
        coVerify { deployments.transition(testDeployment.id, DeploymentStatus.SUPERSEDED) }
        coVerify(exactly = 0) { deployments.transition(newer.id, any(), any(), any()) }
    }

    @Test
    fun `a rejected apply that lands after a newer release re-applies the newer one and is superseded`() = runBlocking {
        server.expect().patch().withPath(routePath + apply).andReturn(422, StatusBuilder().withCode(422).withMessage("the route was rejected").build()).once()
        acceptAll()
        val newer = testDeployment.copy(id = UUID.randomUUID(), status = DeploymentStatus.RUNNING, createdAt = testDeployment.createdAt.plusSeconds(1))
        coEvery { deployments.forService(testService.id, 1) } returnsMany listOf(listOf(testDeployment), listOf(newer))
        Reconciler(app, client).release(testDeployment.id)

        val labels = sent().filter { it.method == "PATCH" && it.path.startsWith(deploymentPath) }
            .map { client.kubernetesSerialization.unmarshal(it.utf8Body, KubeDeployment::class.java).metadata.labels[DEPLOYMENT_LABEL] }
        assertEquals(listOf(testDeployment.id, newer.id).map { it.toString() }, labels)
        coVerify { deployments.transition(testDeployment.id, DeploymentStatus.SUPERSEDED) }
        coVerify(exactly = 0) { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, any(), any()) }
    }

    @Test
    fun `an older deployment is superseded without touching the cluster`() = runBlocking {
        coEvery { deployments.forService(testService.id, 1) } returns listOf(testDeployment.copy(id = UUID.randomUUID()))
        Reconciler(app, client).release(testDeployment.id)
        assertEquals(0, server.requestCount)
        coVerify { deployments.transition(testDeployment.id, DeploymentStatus.SUPERSEDED) }
    }

    @Test
    fun `a release of a deleted service is acknowledged without touching the cluster`() = runBlocking {
        coEvery { services.scope(testService.id) } returns null
        Reconciler(app, client).release(testDeployment.id)
        assertEquals(0, server.requestCount)
        coVerify(exactly = 0) { deployments.transition(any(), any(), any(), any()) }
    }

    @Test
    fun `finished deployments and a running one that is no longer the newest are ignored`() = runBlocking {
        coEvery { deployments.byId(testDeployment.id) } returns testDeployment.copy(status = DeploymentStatus.SUPERSEDED)
        Reconciler(app, client).release(testDeployment.id)
        coEvery { deployments.forService(testService.id, 1) } returns listOf(testDeployment.copy(id = UUID.randomUUID()))
        coEvery { deployments.byId(testDeployment.id) } returns testDeployment.copy(status = DeploymentStatus.RUNNING)
        Reconciler(app, client).release(testDeployment.id)
        assertEquals(0, server.requestCount)
        coVerify(exactly = 0) { deployments.transition(any(), any(), any(), any()) }
    }

    @Test
    fun `web release deletes the cron job and certificates of removed domains`() = runBlocking {
        acceptAll(staleCertificate = "old.acme.dev")
        Reconciler(app, client).release(testDeployment.id)
        assertEquals(listOf(cronJobPath, "$certificatesPath/old.acme.dev"), paths("DELETE"))
    }

    @Test
    fun `listeners and certificates already in place are read with one list and not applied again`() = runBlocking {
        acceptAll(inPlace = true)
        Reconciler(app, client).release(testDeployment.id)
        val sent = sent()
        assertTrue(sent.none { it.method == "PATCH" && (it.path.startsWith(gatewayPath) || it.path.startsWith(certificatesPath)) })
        assertEquals(1, sent.count { it.method == "GET" && it.path.startsWith(certificatesPath) })
    }

    @Test
    fun `a listener a helm upgrade dropped is applied again`() = runBlocking {
        acceptAll()
        Reconciler(app, client).syncCustomDomains()
        assertEquals(listOf(certificatePath, gatewayPath), paths("PATCH"))
    }

    @Test
    fun `a release without custom domains leaves the gateway and certificates alone`() = runBlocking {
        acceptAll()
        coEvery { app.domains.forService(testService.id) } returns release.domains.filter { it.kind == DomainKind.PLATFORM }
        Reconciler(app, client).release(testDeployment.id)
        assertTrue(sent().none { it.path.startsWith(gatewayPath) || it.path.startsWith(certificatesPath) })
    }

    @Test
    fun `edge mode creates no listener or certificate`() = runBlocking {
        every { app.config } returns testConfig(mapOf("LIFTGATE_CLOUDFLARE_ZONE_ID" to "zone", "LIFTGATE_CLOUDFLARE_API_TOKEN" to "token"))
        acceptAll()
        Reconciler(app, client).release(testDeployment.id)
        Reconciler(app, client).reroute(testService.id)
        val patched = paths("PATCH")
        assertTrue(patched.none { it == gatewayPath || it.startsWith(certificatesPath) })
        assertEquals(2, patched.count { it == routePath })
    }

    @Test
    fun `reroute of a service that stopped being web deletes its routing and leaves the status alone`() = runBlocking {
        acceptAll()
        serve(testService.copy(kind = ServiceKind.WORKER, port = null))
        Reconciler(app, client).reroute(testService.id)
        assertEquals(listOf(servicePath, routePath), paths("DELETE"))
        coVerify(exactly = 0) { deployments.transition(any(), any(), any(), any()) }
    }

    @Test
    fun `a worker with a port keeps a private service and gets no route`() = runBlocking {
        acceptAll()
        serve(testService.copy(kind = ServiceKind.WORKER, port = 9000))
        Reconciler(app, client).release(testDeployment.id)
        val sent = sent()
        assertTrue(sent.any { it.method == "PATCH" && it.path == servicePath + apply })
        assertEquals(listOf(routePath, cronJobPath), sent.filter { it.method == "DELETE" }.map { it.path })
    }

    @Test
    fun `reroute applies routing and custom domains and never the workload`() = runBlocking {
        acceptAll()
        Reconciler(app, client).reroute(testService.id)
        assertEquals(listOf(servicePath, routePath, certificatePath, gatewayPath), paths("PATCH"))
    }

    @Test
    fun `reroute during a release routes to the port of the releasing deployment`() = runBlocking {
        acceptAll()
        val running = testDeployment.copy(status = DeploymentStatus.RUNNING, config = testService.spec())
        val releasing = running.copy(id = UUID.randomUUID(), status = DeploymentStatus.RELEASING, createdAt = running.createdAt.plusSeconds(1), config = testService.copy(port = 8080).spec())
        coEvery { deployments.forService(testService.id, 1) } returns listOf(releasing)
        coEvery { deployments.current(testService.id) } returns running
        Reconciler(app, client).reroute(testService.id)
        val service = client.kubernetesSerialization.unmarshal(sent().single { it.path == servicePath + apply }.utf8Body, KubeService::class.java)
        assertEquals(8080, service.spec.ports.single { it.name == "http" }.targetPort.intVal)
    }

    @Test
    fun `teardown of a deleted service removes what carries its id and of a deleted project the namespace`() = runBlocking {
        acceptAll()
        val kinds = listOf("apis/apps/v1/$namespaced/deployments", "apis/batch/v1/$namespaced/cronjobs", "api/v1/$namespaced/services")
        val labelled = (kinds + "apis/gateway.networking.k8s.io/v1/$namespaced/httproutes" + "api/v1/$namespaced/persistentvolumeclaims" + "api/v1/$namespaced/secrets")
            .map { "/$it?labelSelector=liftgate.dev%2Fservice-id%3D${testService.id}" }
        labelled.forEach { server.expect().delete().withPath(it).andReturn(200, StatusBuilder().build()).always() }
        server.expect().delete().withPath(namespacePath).andReturn(200, StatusBuilder().build()).always()
        Reconciler(app, client).teardown(release.namespace, testService.id)
        Reconciler(app, client).teardown(release.namespace, null)
        assertEquals(labelled + namespacePath, paths("DELETE"))
    }

    @Test
    fun `a volume claim is created once, grows in its own storage class and never shrinks`() = runBlocking {
        acceptAll()
        serve(testService.copy(replicas = 1, volume = Volume("/data", 5)))
        val claimPath = "/api/v1/$namespaced/persistentvolumeclaims/api-data"
        val volumed = testRelease(testService.copy(replicas = 1, volume = Volume("/data", 5)))
        accept(claimPath, requireNotNull(Resources.volumeClaim(volumed, null)))
        Reconciler(app, client).release(testDeployment.id)
        server.expect().get().withPath(claimPath).andReturn(200, requireNotNull(Resources.volumeClaim(volumed.copy(service = volumed.service.copy(volume = Volume("/data", 2))), "fast"))).once()
        Reconciler(app, client).release(testDeployment.id)
        server.expect().get().withPath(claimPath).andReturn(200, requireNotNull(Resources.volumeClaim(volumed.copy(service = volumed.service.copy(volume = Volume("/data", 8))), "fast"))).once()
        Reconciler(app, client).release(testDeployment.id)

        val patches = sent().filter { it.method == "PATCH" }
        val applied = patches.filter { it.path.startsWith(claimPath) }.map { client.kubernetesSerialization.unmarshal(it.utf8Body, PersistentVolumeClaim::class.java).spec }
        assertEquals(listOf(null to Quantity("5Gi"), "fast" to Quantity("5Gi")), applied.map { it.storageClassName to it.resources.requests["storage"] })
        val deployment = patches.first { it.path.startsWith(deploymentPath) }.utf8Body
        listOf("\"type\":\"Recreate\"", "\"claimName\":\"api-data\"", "\"mountPath\":\"/data\"").forEach { assertTrue(it in deployment, it) }
    }

    @Test
    fun `a rollback to a deployment from before the volume keeps the volume mounted with one replica`() = runBlocking {
        acceptAll()
        val volumed = testRelease(testService.copy(replicas = 1, volume = Volume("/data", 5)))
        serve(volumed.service)
        accept("/api/v1/$namespaced/persistentvolumeclaims/api-data", requireNotNull(Resources.volumeClaim(volumed, null)))
        coEvery { deployments.byId(testDeployment.id) } returns testDeployment.copy(config = testService.spec())
        Reconciler(app, client).release(testDeployment.id)

        val deployment = client.kubernetesSerialization.unmarshal(sent().single { it.method == "PATCH" && it.path.startsWith(deploymentPath) }.utf8Body, KubeDeployment::class.java)
        assertEquals("Recreate" to 1, deployment.spec.strategy.type to deployment.spec.replicas)
        assertEquals("api-data", deployment.spec.template.spec.volumes.single().persistentVolumeClaim.claimName)
        assertEquals("/data", deployment.spec.template.spec.containers.single().volumeMounts.single().mountPath)
    }

    @Test
    fun `a volume its storage class cannot grow keeps its size and the release still applies the workload`() = runBlocking {
        acceptAll()
        val volumed = testRelease(testService.copy(replicas = 1, volume = Volume("/data", 5)))
        serve(volumed.service)
        val claimPath = "/api/v1/$namespaced/persistentvolumeclaims/api-data"
        server.expect().get().withPath(claimPath).andReturn(200, requireNotNull(Resources.volumeClaim(volumed.copy(service = volumed.service.copy(volume = Volume("/data", 2))), "standard"))).always()
        server.expect().patch().withPath(claimPath + apply)
            .andReturn(403, StatusBuilder().withCode(403).withMessage("only dynamically provisioned pvc can be resized and the storageclass that provisions the pvc must support resize").build()).always()
        Reconciler(app, client).release(testDeployment.id)

        val patched = paths("PATCH")
        assertTrue(claimPath in patched && deploymentPath in patched, patched.toString())
        coVerify(exactly = 0) { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, any(), any()) }
    }

    @Test
    fun `a database applies its environment, policy, reader binding, backup store, cluster and schedule, and once deleted loses its cluster`() = runBlocking {
        every { app.config } returns testConfig(
            mapOf(
                "LIFTGATE_DATABASE_READER_ROLE" to "liftgate-database-reader",
                "LIFTGATE_DATABASE_BACKUP_DESTINATION" to "s3://tenants",
                "LIFTGATE_DATABASE_BACKUP_ACCESS_KEY_ID" to "key",
                "LIFTGATE_DATABASE_BACKUP_SECRET_ACCESS_KEY" to "secret",
                "LIFTGATE_DATABASE_BACKUP_EGRESS" to "192.0.2.10/32:3900",
            ),
        )
        val database = Database(UUID.randomUUID(), testEnvironment.id, "main", 1, 500, 512, null, null, Instant.now())
        val scope = DatabaseScope(database, testEnvironment, testProject, testOrg)
        coEvery { databases.scope(database.id) } returnsMany listOf(scope, scope, null)
        acceptAll()
        val objects = listOf(
            "/apis/networking.k8s.io/v1/$namespaced/networkpolicies/allow-databases",
            "/apis/rbac.authorization.k8s.io/v1/$namespaced/rolebindings/liftgate-database-reader",
            "/api/v1/$namespaced/secrets/liftgate-backup",
            "/apis/barmancloud.cnpg.io/v1/$namespaced/objectstores/liftgate-backup",
            "/apis/postgresql.cnpg.io/v1/$namespaced/clusters/main",
            "/apis/postgresql.cnpg.io/v1/$namespaced/scheduledbackups/main",
        )
        objects.forEach { server.expect().patch().withPath(it + apply).andReturn(200, "{}").always() }
        val deleted = listOf("scheduledbackups", "clusters").map { "/apis/postgresql.cnpg.io/v1/$namespaced/$it?labelSelector=liftgate.dev%2Fdatabase-id%3D${database.id}" }
        deleted.forEach { server.expect().delete().withPath(it).andReturn(200, StatusBuilder().build()).always() }

        repeat(2) { Reconciler(app, client).database(release.namespace, database.id) }
        val sent = sent()
        assertEquals(listOf(namespacePath, quotaPath) + policyPaths + objects, sent.filter { it.method == "PATCH" }.map { it.path.removeSuffix(apply) })
        assertEquals(deleted, sent.filter { it.method == "DELETE" }.map { it.path })
    }

    @Test
    fun `a database deleted while it was being applied loses the cluster that apply created`() = runBlocking {
        val database = Database(UUID.randomUUID(), testEnvironment.id, "main", 1, 500, 512, null, null, Instant.now())
        coEvery { databases.scope(database.id) } returnsMany listOf(DatabaseScope(database, testEnvironment, testProject, testOrg), null)
        acceptAll()
        server.expect().patch().withPath("/apis/networking.k8s.io/v1/$namespaced/networkpolicies/allow-databases$apply").andReturn(200, "{}").always()
        server.expect().patch().withPath("/apis/postgresql.cnpg.io/v1/$namespaced/clusters/main$apply").andReturn(200, "{}").always()
        val deleted = listOf("scheduledbackups", "clusters").map { "/apis/postgresql.cnpg.io/v1/$namespaced/$it?labelSelector=liftgate.dev%2Fdatabase-id%3D${database.id}" }
        deleted.forEach { server.expect().delete().withPath(it).andReturn(200, StatusBuilder().build()).always() }

        Reconciler(app, client).database(release.namespace, database.id)
        val sent = sent()
        assertTrue(sent.any { it.method == "PATCH" && it.path.startsWith("/apis/postgresql.cnpg.io/v1/$namespaced/clusters/main") })
        assertEquals(deleted, sent.filter { it.method == "DELETE" }.map { it.path })
    }

    @Test
    fun `cron services become a cron job and run as soon as they are applied`() = runBlocking {
        acceptAll()
        serve(testService.copy(kind = ServiceKind.CRON, port = null, cronSchedule = "0 * * * *"))
        Reconciler(app, client).release(testDeployment.id)

        val sent = sent()
        assertTrue(sent.any { it.method == "PATCH" && it.path.startsWith(cronJobPath) })
        assertEquals(listOf(servicePath, routePath, deploymentPath), sent.filter { it.method == "DELETE" }.map { it.path })
        coVerify { deployments.transition(testDeployment.id, DeploymentStatus.RUNNING) }
    }
}
