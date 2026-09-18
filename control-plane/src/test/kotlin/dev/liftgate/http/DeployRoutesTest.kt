package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.Access
import dev.liftgate.auth.Sessions
import dev.liftgate.build.GitHubApp
import dev.liftgate.deploy.Builds
import dev.liftgate.k8s.testBuild
import dev.liftgate.k8s.testEnvironment
import dev.liftgate.k8s.testOrg
import dev.liftgate.k8s.testProject
import dev.liftgate.k8s.testService
import dev.liftgate.org.User
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.ktor.client.request.cookie
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class DeployRoutesTest {
    private val builds = mockk<Builds> { coEvery { request(any(), any(), any(), any()) } returns testBuild }
    private val app = mockk<App> {
        every { services } returns mockk<Services> { coEvery { scope(testService.id) } returns ServiceScope(testService, testEnvironment, testProject, testOrg) }
        every { access } returns mockk<Access>(relaxUnitFun = true)
        every { sessions } returns mockk<Sessions> { coEvery { resolve("s") } returns User(UUID.randomUUID(), 1, "dean", null, null, null) }
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { oauth } returns null
        every { config } returns testConfig()
        every { github } returns null
        every { this@mockk.builds } returns this@DeployRoutesTest.builds
    }

    private suspend fun ApplicationTestBuilder.deploy(body: String) = client.post("/api/v1/services/${testService.id}/deploy") {
        cookie(SESSION_COOKIE, "s")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `deploy without a ref builds the head of the environment branch`() = testApplication {
        every { app.github } returns mockk<GitHubApp> { coEvery { branchHead(42, "acme/shop", "main") } returns ("abc123" to "ship it") }
        application { liftgate(app) }
        assertEquals(HttpStatusCode.Created, deploy("{}").status)
        coVerify { builds.request(testService.id, "abc123", "ship it", "main") }
    }

    @Test
    fun `deploy with a full sha builds it without asking github`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.Created, deploy("""{"ref":"dddddddddddddddddddddddddddddddddddddddd"}""").status)
        coVerify { builds.request(testService.id, "dddddddddddddddddddddddddddddddddddddddd", null, "main") }
    }

    @Test
    fun `deploy with a branch ref builds the sha github resolves it to`() = testApplication {
        every { app.github } returns mockk<GitHubApp> { coEvery { branchHead(42, "acme/shop", "release/1") } returns ("abc123" to "cut") }
        application { liftgate(app) }
        assertEquals(HttpStatusCode.Created, deploy("""{"ref":"release/1"}""").status)
        coVerify { builds.request(testService.id, "abc123", "cut", "main") }
    }

    @Test
    fun `deploy without a ref needs the github app and refs must be well formed`() = testApplication {
        application { liftgate(app) }
        val response = deploy("{}")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue("ref_required" in response.bodyAsText())
        assertEquals(HttpStatusCode.UnprocessableEntity, deploy("""{"ref":"bad ref"}""").status)
        coVerify(exactly = 0) { builds.request(any(), any(), any(), any()) }
    }
}
