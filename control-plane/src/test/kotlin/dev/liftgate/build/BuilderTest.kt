package dev.liftgate.build

import dev.liftgate.App
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Builds
import dev.liftgate.events.Nats
import dev.liftgate.k8s.testBuild
import dev.liftgate.k8s.testDeployment
import dev.liftgate.k8s.testEnvironment
import dev.liftgate.k8s.testOrg
import dev.liftgate.k8s.testProject
import dev.liftgate.k8s.testService
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * @author Dean
 * @date 9/17/2026
 */
@EnableKubernetesMockClient(crud = true)
class BuilderTest {
    lateinit var client: KubernetesClient

    private val queued = testBuild.copy(status = BuildStatus.QUEUED, imageRef = null)
    private val image = "registry.liftgate.internal/acme/shop-api:abc123"
    private val builds = mockk<Builds>(relaxUnitFun = true) {
        coEvery { byId(queued.id) } returns queued
        coEvery { markSucceeded(queued.id, any()) } returns testDeployment
    }
    private val app = mockk<App> {
        every { config } returns testConfig()
        every { this@mockk.builds } returns this@BuilderTest.builds
        every { services } returns mockk<Services> { coEvery { scope(testService.id) } returns ServiceScope(testService, testEnvironment, testProject, testOrg) }
        every { github } returns mockk<GitHubApp> { coEvery { installationToken(42) } returns "ghs_token" }
        every { nats } returns mockk<Nats>(relaxed = true)
    }

    private fun job() = client.batch().v1().jobs().inNamespace("liftgate-build").withName(BuildJobs.name(queued.id))

    private fun finishedPod() = PodBuilder()
        .withNewMetadata().withName("build-pod").withNamespace("liftgate-build").addToLabels(BUILD_LABEL, queued.id.toString()).endMetadata()
        .withNewStatus().withPhase("Succeeded").endStatus()
        .build()

    private fun build(status: JobStatus) = runBlocking {
        client.resource(finishedPod()).create()
        val building = async(Dispatchers.Default) { Builder(app, client).build(queued.id) }
        job().waitUntilCondition({ it != null }, 10, TimeUnit.SECONDS)
        job().editStatus { JobBuilder(it).withStatus(status).build() }
        building.await()
    }

    @Test
    fun `a succeeded job releases the image and is deleted`() {
        build(JobStatusBuilder().withSucceeded(1).build())
        coVerify(exactly = 1) { builds.markRunning(queued.id) }
        coVerify(exactly = 1) { builds.markSucceeded(queued.id, image) }
        coVerify(exactly = 0) { builds.markFailed(any(), any()) }
        assertNull(job().get())
    }

    @Test
    fun `a failed job fails the build and is kept for inspection`() {
        build(JobStatusBuilder().withFailed(1).build())
        coVerify(exactly = 1) { builds.markFailed(queued.id, "the build job failed") }
        coVerify(exactly = 0) { builds.markSucceeded(any(), any()) }
        assertNotNull(job().get())
        assertEquals("ghs_token", client.secrets().inNamespace("liftgate-build").withName(BuildJobs.name(queued.id)).get().stringData["token"])
    }

    @Test
    fun `a build without the github app fails immediately`() = runBlocking {
        every { app.github } returns null
        Builder(app, client).build(queued.id)
        coVerify { builds.markFailed(queued.id, "the GitHub App is not configured") }
        assertNull(job().get())
    }

    @Test
    fun `finished builds are not run again`() = runBlocking {
        coEvery { builds.byId(queued.id) } returns testBuild
        Builder(app, client).build(queued.id)
        coVerify(exactly = 0) { builds.markRunning(any()) }
        assertNull(job().get())
    }
}
