package dev.liftgate.build

import dev.liftgate.App
import dev.liftgate.config.GitHubConfig
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Builds
import dev.liftgate.events.Nats
import dev.liftgate.http.json
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
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
@EnableKubernetesMockClient(crud = true)
class BuilderTest {
    lateinit var client: KubernetesClient
    lateinit var server: KubernetesMockServer

    private val queued = testBuild.copy(status = BuildStatus.QUEUED, imageRef = null)
    private val image = "registry.liftgate.internal/acme/shop-api:abc123"
    private var permission = HttpStatusCode.OK to """{"permission":"write"}"""
    private val github = GitHubApp(
        GitHubConfig("1", TestKeys.privateKeyPem, "webhook", "client", "client-secret"),
        HttpClient(MockEngine { request ->
            val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when (request.url.encodedPath) {
                "/app/installations/42/access_tokens" -> respond("""{"token":"ghs_token"}""", HttpStatusCode.Created, headers)
                "/repos/acme/shop/collaborators/dean/permission" -> respond(permission.second, permission.first, headers)
                else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, headers)
            }
        }) { install(ContentNegotiation) { json(json) } },
    )
    private val builds = mockk<Builds>(relaxUnitFun = true) {
        coEvery { byId(queued.id) } returns queued
        coEvery { markSucceeded(queued.id, any()) } returns testDeployment
    }
    private val registryTokens = mockk<RegistryTokens>(relaxUnitFun = true) { coEvery { issue(queued.id) } returns "registry-password" }
    private val app = mockk<App> {
        every { config } returns testConfig()
        every { this@mockk.builds } returns this@BuilderTest.builds
        every { services } returns mockk<Services> {
            coEvery { scope(testService.id) } returns ServiceScope(testService, testEnvironment, testProject.copy(importedByLogin = "dean"), testOrg)
        }
        every { github } returns this@BuilderTest.github
        every { registryTokens } returns this@BuilderTest.registryTokens
        every { nats } returns mockk<Nats>(relaxed = true)
    }

    private fun job() = client.batch().v1().jobs().inNamespace("liftgate-build").withName(BuildJobs.name(queued.id))

    private fun secret() = client.secrets().inNamespace("liftgate-build").withName(BuildJobs.name(queued.id)).get()

    private fun finishedPod() = PodBuilder()
        .withNewMetadata().withName("build-pod").withNamespace("liftgate-build").addToLabels(BUILD_LABEL, queued.id.toString()).endMetadata()
        .withNewStatus().withPhase("Succeeded").endStatus()
        .build()

    private fun build(status: JobStatus) = runBlocking {
        val building = async(Dispatchers.Default) { Builder(app, client).build(queued.id) }
        job().waitUntilCondition({ it != null }, 10, TimeUnit.SECONDS)
        job().editStatus { JobBuilder(it).withStatus(status).build() }
        client.resource(finishedPod()).create()
        building.await()
    }

    private fun jobCreations() = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }
        .count { it.method == "POST" && it.path.orEmpty().substringBefore('?').endsWith("/namespaces/liftgate-build/jobs") }

    @Test
    fun `a succeeded job releases the image and is deleted`() {
        build(JobStatusBuilder().withSucceeded(1).build())
        coVerify(exactly = 1) { builds.markRunning(queued.id) }
        coVerify(exactly = 1) { builds.markSucceeded(queued.id, image) }
        coVerify(exactly = 0) { builds.markFailed(any(), any()) }
        coVerify(exactly = 1) { registryTokens.revoke(queued.id) }
        assertNull(job().get())
    }

    @Test
    fun `a failed job fails the build and is kept for inspection`() {
        build(JobStatusBuilder().withFailed(1).build())
        coVerify(exactly = 1) { builds.markFailed(queued.id, "the build job failed") }
        coVerify(exactly = 0) { builds.markSucceeded(any(), any()) }
        coVerify(exactly = 0) { registryTokens.issue(any()) }
        assertNotNull(job().get())
        assertEquals(mapOf("token" to "ghs_token"), secret().stringData)
    }

    @Test
    fun `an importer without write access fails the build before any job exists`() = runBlocking {
        listOf(HttpStatusCode.OK to """{"permission":"read"}""", HttpStatusCode.NotFound to """{"message":"Not Found"}""").forEach {
            permission = it
            Builder(app, client).build(queued.id)
            assertNull(job().get())
        }
        coVerify(exactly = 2) { builds.markFailed(queued.id, "the GitHub account that imported acme/shop no longer has write access; re-import it") }
        coVerify(exactly = 0) { builds.markSucceeded(any(), any()) }
    }

    @Test
    fun `a project imported before the check existed builds without it`() {
        every { app.services } returns mockk<Services> { coEvery { scope(testService.id) } returns ServiceScope(testService, testEnvironment, testProject, testOrg) }
        permission = HttpStatusCode.NotFound to """{"message":"Not Found"}"""
        build(JobStatusBuilder().withSucceeded(1).build())
        coVerify(exactly = 1) { builds.markSucceeded(queued.id, image) }
    }

    @Test
    fun `a failed success write reattaches to the finished job instead of building again`() {
        every { app.config } returns testConfig(mapOf("LIFTGATE_REGISTRY_AUTH" to "token"))
        coEvery { builds.markSucceeded(queued.id, image) } throws IllegalStateException("database unavailable") andThen testDeployment
        assertFailsWith<IllegalStateException> { build(JobStatusBuilder().withSucceeded(1).build()) }
        assertNotNull(job().get())
        runBlocking { Builder(app, client).build(queued.id) }
        coVerify(exactly = 2) { builds.markSucceeded(queued.id, image) }
        coVerify(exactly = 1) { registryTokens.issue(queued.id) }
        assertEquals(1, jobCreations())
        assertNull(job().get())
    }

    @Test
    fun `token auth gives the build its own registry login`() {
        every { app.config } returns testConfig(mapOf("LIFTGATE_REGISTRY_AUTH" to "token"))
        build(JobStatusBuilder().withFailed(1).build())
        val config = json.parseToJsonElement(secret().stringData.getValue(".dockerconfigjson")).toString()
        assertTrue("registry.liftgate.internal" in config)
        assertEquals(BuildJobs.name(queued.id), job().get().spec.template.spec.volumes.single { it.name == "docker-config" }.secret.secretName)
        coVerify(exactly = 1) { registryTokens.issue(queued.id) }
        coVerify(exactly = 1) { registryTokens.revoke(queued.id) }
    }

    @Test
    fun `a build without the github app fails immediately`() = runBlocking {
        every { app.github } returns null
        Builder(app, client).build(queued.id)
        coVerify { builds.markFailed(queued.id, "the GitHub App is not configured") }
        assertNull(job().get())
    }

    @Test
    fun `builds of a suspended org are refused`() = runBlocking {
        coEvery { app.services.scope(testService.id) } returns ServiceScope(testService, testEnvironment, testProject, testOrg.copy(suspendedAt = Instant.now()))
        Builder(app, client).build(queued.id)
        coVerify { builds.markFailed(queued.id, "the organization is suspended") }
        coVerify(exactly = 0) { builds.markRunning(any()) }
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
