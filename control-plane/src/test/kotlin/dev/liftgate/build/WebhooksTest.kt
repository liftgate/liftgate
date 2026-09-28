package dev.liftgate.build

import dev.liftgate.App
import dev.liftgate.cache.Cache
import dev.liftgate.deploy.Builds
import dev.liftgate.http.WEBHOOKS_PER_MINUTE
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
import io.mockk.verify
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * @author Dean
 * @date 9/17/2026
 */
class WebhooksTest {
    private val secret = "It's a Secret to Everybody"
    private val builds = mockk<Builds> { coEvery { request(any(), any(), any(), any()) } returns testBuild }
    private val cache = mockk<Cache> { every { allow(any(), any(), any()) } returns true }
    private val app = mockk<App> {
        every { this@mockk.cache } returns this@WebhooksTest.cache
        every { config } returns testConfig().copy(githubWebhookSecret = secret)
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { this@mockk.builds } returns this@WebhooksTest.builds
        every { projects } returns mockk<Projects> {
            coEvery { environmentsForRepo(any(), any(), any()) } returns emptyList()
            coEvery { environmentsForRepo(42, "acme/shop", "main") } returns listOf(testEnvironment)
        }
        every { services } returns mockk<Services> { coEvery { forEnvironment(testEnvironment.id) } returns listOf(testService) }
    }

    private fun push(ref: String = "refs/heads/main", deleted: Boolean = false, installation: Long = 42, forced: Boolean = false, commits: String = "[]") =
        """{"ref":"$ref","after":"abc123","deleted":$deleted,"forced":$forced,"commits":$commits,"repository":{"full_name":"acme/shop"},"installation":{"id":$installation},"head_commit":{"message":"ship it"}}"""

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
    fun `a push builds only the services whose watch paths or root directory match a changed file, a forced push or one past the matching budget builds them all, and every push spends the webhook limit`() = testApplication {
        val web = testService.copy(id = UUID.randomUUID(), slug = "web", rootDir = "/apps/web")
        val api = testService.copy(id = UUID.randomUUID(), slug = "api", rootDir = "/apps/api", watchPaths = listOf("apps/api/**", "packages/**"))
        every { app.services } returns mockk<Services> { coEvery { forEnvironment(testEnvironment.id) } returns listOf(web, api) }
        application { liftgate(app) }
        val commits = """[{"added":["apps/web/app/page.tsx"],"modified":["apps/web/package.json"],"removed":[]},{"added":[],"modified":[],"removed":["apps/web/old.css"]}]"""
        val flood = """[{"added":["docs/${"x".repeat(200_000)}"],"modified":[],"removed":[]}]"""
        val docs = """[{"added":["docs/intro.md"],"modified":[],"removed":[]}]"""
        listOf(push(commits = commits), push(commits = commits, forced = true), push(commits = flood), push(commits = docs)).forEach { body ->
            val response = client.post("/api/v1/webhooks/github") {
                header("X-GitHub-Event", "push")
                header("X-Hub-Signature-256", sign(body))
                setBody(body)
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
        coVerify(exactly = 3) { builds.request(web.id, "abc123", "ship it", "main") }
        coVerify(exactly = 2) { builds.request(api.id, "abc123", "ship it", "main") }
        verify(exactly = 4) { cache.allow("rate:webhook:42", WEBHOOKS_PER_MINUTE, 1.minutes) }
    }

    @Test
    fun `a double star directory in a watch path also matches no directory at all`() {
        val docs = testService.spec().copy(watchPaths = listOf("**/*.md", "apps/**/test/*.ts"))
        listOf("README.md", "docs/guide/intro.md", "apps/test/unit.ts", "apps/web/test/unit.ts").forEach { assertTrue(docs.watches(listOf(it)), it) }
        listOf("src/index.ts", "apps/web/unit.ts").forEach { assertFalse(docs.watches(listOf(it)), it) }
    }

    @Test
    fun `watch paths take brackets literally, and without watch paths the normalized root directory is watched`() {
        val page = testService.spec().copy(watchPaths = listOf("apps/web/app/[slug]/page.?sx"))
        assertTrue(page.watches(listOf("apps/web/app/[slug]/page.tsx")))
        listOf("apps/web/app/s/page.tsx", "apps/web/app/[slug]/page./sx").forEach { assertFalse(page.watches(listOf(it)), it) }
        val root = testService.spec().copy(rootDir = "./apps//web/")
        assertTrue(root.watches(listOf("apps/web/package.json")))
        assertFalse(root.watches(listOf("apps/webhooks/package.json")))
    }

    @Test
    fun `a watch path full of wildcards is matched without backtracking`() {
        val spec = testService.spec().copy(watchPaths = listOf("*a".repeat(49) + "b"))
        assertTimeoutPreemptively(Duration.ofSeconds(5)) { assertFalse(spec.watches(listOf("a".repeat(4096)))) }
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
    fun `untracked branches, tags, deleted branches, other installations and other events build nothing and spend none of the webhook limit`() = testApplication {
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
        verify(exactly = 0) { cache.allow(any(), any(), any()) }
    }

    @Test
    fun `pushes past their installation's limit are refused`() = testApplication {
        every { cache.allow("rate:webhook:42", WEBHOOKS_PER_MINUTE, 1.minutes) } returns false
        application { liftgate(app) }
        val response = client.post("/api/v1/webhooks/github") {
            header("X-GitHub-Event", "push")
            header("X-Hub-Signature-256", sign(push()))
            setBody(push())
        }
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        coVerify(exactly = 0) { builds.request(any(), any(), any(), any()) }
    }
}
