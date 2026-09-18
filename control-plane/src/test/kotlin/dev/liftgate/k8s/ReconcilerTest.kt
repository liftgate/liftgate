package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.deploy.Builds
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.Deployments
import dev.liftgate.domain.DomainKind
import dev.liftgate.domain.Domains
import dev.liftgate.service.EnvVars
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    private val app = mockk<App>().also {
        every { it.config } returns testConfig(mapOf("LIFTGATE_RUNTIME_CLASS" to "gvisor"))
        every { it.deployments } returns deployments
        every { it.services } returns services
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

    private fun acceptAll(staleCertificate: String? = null) {
        val certificates = GenericKubernetesResourceList().apply {
            items = listOfNotNull(staleCertificate).map { Resources.certificate(custom.copy(hostname = it), "liftgate-system") }
        }
        val gateway = Resources.gatewayListeners(listOf(custom), "liftgate-system", "liftgate")
        server.expect().get().withPath(gatewayPath).andReturn(200, gateway).always()
        accept(gatewayPath, gateway)
        accept(namespacePath, Resources.namespace(release))
        accept(quotaPath, Resources.resourceQuota(release))
        accept(secretPath, Resources.secret(release))
        accept(servicePath, Resources.service(release))
        accept(deploymentPath, Resources.deployment(release, null))
        accept(cronJobPath, Resources.cronJob(release, null))
        accept(routePath, Resources.httpRoute(release, "liftgate-system", "liftgate"))
        accept(certificatePath, Resources.certificate(custom, "liftgate-system"))
        Resources.networkPolicies(release, "liftgate-system").zip(policyPaths).forEach { (policy, path) -> accept(path, policy) }
        server.expect().get().withPath("$certificatesPath?labelSelector=liftgate.dev%2Fmanaged%3Dtrue").andReturn(200, certificates).always()
    }

    private fun sent() = List(server.requestCount) { server.takeRequest() }

    private fun paths(method: String) = sent().filter { it.method == method }.map { it.path.removeSuffix(apply) }

    @Test
    fun `release server-side applies every object of a web service and marks it releasing`() = runBlocking {
        acceptAll()
        Reconciler(app, client).release(testDeployment.id)

        val applied = sent().filter { it.method == "PATCH" }
        assertEquals((listOf(namespacePath, quotaPath, secretPath) + policyPaths + deploymentPath + servicePath + routePath + certificatePath + gatewayPath).map { it + apply }, applied.map { it.path })
        assertTrue("\"hostname\":\"api.acme.dev\"" in applied.last().utf8Body)
        assertTrue(applied.all { it.getHeader("Content-Type").startsWith("application/apply-patch+yaml") })
        assertTrue("\"runtimeClassName\":\"gvisor\"" in applied.single { it.path.startsWith(deploymentPath) }.utf8Body)
        coVerify(exactly = 1) { deployments.transition(testDeployment.id, DeploymentStatus.RELEASING) }
        coVerify(exactly = 0) { deployments.transition(testDeployment.id, DeploymentStatus.RUNNING, any(), any()) }
        coVerify(exactly = 0) { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, any(), any()) }
    }

    @Test
    fun `a rejected apply fails the deployment`() = runBlocking {
        Reconciler(app, client).release(testDeployment.id)
        coVerify { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, 0, any()) }
    }

    @Test
    fun `an older deployment is superseded without touching the cluster`() = runBlocking {
        coEvery { deployments.forService(testService.id, 1) } returns listOf(testDeployment.copy(id = UUID.randomUUID()))
        Reconciler(app, client).release(testDeployment.id)
        assertEquals(0, server.requestCount)
        coVerify { deployments.transition(testDeployment.id, DeploymentStatus.SUPERSEDED) }
    }

    @Test
    fun `finished deployments are ignored`() = runBlocking {
        coEvery { deployments.byId(testDeployment.id) } returns testDeployment.copy(status = DeploymentStatus.SUPERSEDED)
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
    fun `reroute of a service that stopped being web deletes its routing and leaves the status alone`() = runBlocking {
        acceptAll()
        serve(testService.copy(kind = ServiceKind.WORKER, port = null))
        Reconciler(app, client).reroute(testService.id)
        assertEquals(listOf(servicePath, routePath), paths("DELETE"))
        coVerify(exactly = 0) { deployments.transition(any(), any(), any(), any()) }
    }

    @Test
    fun `reroute applies routing and custom domains and never the workload`() = runBlocking {
        acceptAll()
        Reconciler(app, client).reroute(testService.id)
        assertEquals(listOf(servicePath, routePath, certificatePath, gatewayPath), paths("PATCH"))
    }

    @Test
    fun `teardown of a deleted service removes what carries its id and of a deleted project the namespace`() = runBlocking {
        acceptAll()
        val kinds = listOf("apis/apps/v1/$namespaced/deployments", "apis/batch/v1/$namespaced/cronjobs", "api/v1/$namespaced/services", "api/v1/$namespaced/secrets")
        val labelled = (kinds + "apis/gateway.networking.k8s.io/v1/$namespaced/httproutes")
            .map { "/$it?labelSelector=liftgate.dev%2Fservice-id%3D${testService.id}" }
        labelled.forEach { server.expect().delete().withPath(it).andReturn(200, StatusBuilder().build()).always() }
        server.expect().delete().withPath(namespacePath).andReturn(200, StatusBuilder().build()).always()
        Reconciler(app, client).teardown(release.namespace, testService.id)
        Reconciler(app, client).teardown(release.namespace, null)
        assertEquals(labelled + namespacePath, paths("DELETE"))
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
