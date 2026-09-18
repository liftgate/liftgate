package dev.liftgate.build

import dev.liftgate.App
import dev.liftgate.config.GitHubConfig
import dev.liftgate.deploy.Builds
import dev.liftgate.http.liftgate
import dev.liftgate.k8s.testBuild
import dev.liftgate.k8s.testEnvironment
import dev.liftgate.k8s.testService
import dev.liftgate.project.Projects
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class WebhooksTest {
    private val secret = "It's a Secret to Everybody"
    private val builds = mockk<Builds> { coEvery { request(any(), any(), any(), any()) } returns testBuild }
    private val app = mockk<App> {
        every { config } returns testConfig().copy(github = GitHubConfig("1", "unused", secret, "client", "client-secret"))
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { this@mockk.builds } returns this@WebhooksTest.builds
        every { projects } returns mockk<Projects> {
            coEvery { environmentsForRepo(any(), any(), any()) } returns emptyList()
            coEvery { environmentsForRepo(42, "acme/shop", "main") } returns listOf(testEnvironment)
        }
        every { services } returns mockk<Services> { coEvery { forEnvironment(testEnvironment.id) } returns listOf(testService) }
    }

    private fun push(ref: String = "refs/heads/main", deleted: Boolean = false, installation: Long = 42) =
        """{"ref":"$ref","after":"abc123","deleted":$deleted,"repository":{"full_name":"acme/shop"},"installation":{"id":$installation},"head_commit":{"message":"ship it"}}"""

    private fun sign(body: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        "sha256=" + doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `verify accepts the signature github documents`() =
        assertTrue(Webhooks.verify(secret, "Hello, World!".toByteArray(), "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"))

    @Test
    fun `verify rejects wrong, malformed and missing signatures`() {
        val body = "Hello, World!".toByteArray()
        assertFalse(Webhooks.verify(secret, "Hello, World?".toByteArray(), "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"))
        assertFalse(Webhooks.verify("another secret", body, "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"))
        assertFalse(Webhooks.verify(secret, body, "757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"))
        assertFalse(Webhooks.verify(secret, body, null))
    }

    @Test
    fun `a signed push on a tracked branch requests a build for every service of the environment`() = testApplication {
        application { liftgate(app) }
        val response = client.post("/api/v1/webhooks/github") {
            header("X-GitHub-Event", "push")
            header("X-Hub-Signature-256", sign(push()))
            setBody(push())
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify(exactly = 1) { builds.request(testService.id, "abc123", "ship it", "main") }
    }

    @Test
    fun `a push that does not match its signature is rejected`() = testApplication {
        application { liftgate(app) }
        val response = client.post("/api/v1/webhooks/github") {
            header("X-GitHub-Event", "push")
            header("X-Hub-Signature-256", sign(push()))
            setBody(push(ref = "refs/heads/main "))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        coVerify(exactly = 0) { builds.request(any(), any(), any(), any()) }
    }

    @Test
    fun `untracked branches, tags, deleted branches, other installations and other events build nothing`() = testApplication {
        application { liftgate(app) }
        listOf("push" to push(ref = "refs/heads/feature"), "push" to push(ref = "refs/tags/v1"), "push" to push(deleted = true), "push" to push(installation = 7), "ping" to push()).forEach { (event, body) ->
            val response = client.post("/api/v1/webhooks/github") {
                header("X-GitHub-Event", event)
                header("X-Hub-Signature-256", sign(body))
                setBody(body)
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
        coVerify(exactly = 0) { builds.request(any(), any(), any(), any()) }
    }
}
