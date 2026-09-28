package dev.liftgate.notify

import dev.liftgate.App
import dev.liftgate.build.GitHubApp
import dev.liftgate.build.TestKeys
import dev.liftgate.config.GitHubConfig
import dev.liftgate.deploy.Build
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Builds
import dev.liftgate.deploy.Deployment
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.Deployments
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * @author Dean
 * @date 9/27/2026
 */
class CommitStatusesTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var answer = HttpStatusCode.Created
    private var rateLimited = false
    private var onStatus = {}
    private val github = GitHubApp(
        GitHubConfig("1", TestKeys.privateKeyPem),
        HttpClient(MockEngine { request ->
            requests += request
            val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when (request.url.encodedPath) {
                "/app/installations/42/access_tokens" -> respond("""{"token":"ghs_status"}""", HttpStatusCode.Created, headers)
                "/repos/acme/shop/statuses/abc123" -> {
                    onStatus()
                    respond("{}", answer, if (rateLimited) headersOf("x-ratelimit-remaining", "0") else headers)
                }
                else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, headers)
            }
        }) { install(ContentNegotiation) { json(json) } },
    )
    private var build = testBuild.copy(status = BuildStatus.FAILED, error = "the build job failed")
    private var deployments = emptyList<Deployment>()
    private val app = mockk<App> {
        every { config } returns testConfig()
        every { github } returns this@CommitStatusesTest.github
        every { builds } returns mockk<Builds> { coEvery { byId(testBuild.id) } answers { this@CommitStatusesTest.build } }
        every { this@mockk.deployments } returns mockk<Deployments> { coEvery { forService(testService.id) } answers { this@CommitStatusesTest.deployments } }
        every { services } returns mockk<Services> { coEvery { scope(testService.id) } returns ServiceScope(testService, testEnvironment, testProject, testOrg) }
    }

    private fun sent(index: Int) = json.parseToJsonElement((requests[index].body as TextContent).text)

    private suspend fun post(build: Build, deployment: Deployment? = null): Pair<String, String> {
        this.build = build
        deployments = listOfNotNull(deployment, testDeployment.copy(id = UUID.randomUUID(), buildId = UUID.randomUUID(), status = DeploymentStatus.RUNNING))
        CommitStatuses(app).post(testBuild.id)
        return sent(requests.lastIndex).jsonObject.let { it.getValue("state").jsonPrimitive.content to it.getValue("description").jsonPrimitive.content }
    }

    @Test
    fun `a failed build posts state failure with a token that can only write statuses`() = runBlocking {
        CommitStatuses(app).post(testBuild.id)
        assertEquals(json.parseToJsonElement("""{"repositories":["shop"],"permissions":{"statuses":"write"}}"""), sent(0))
        assertEquals("Bearer ghs_status", requests[1].headers[HttpHeaders.Authorization])
        assertEquals(
            json.parseToJsonElement(
                """{"state":"failure","target_url":"http://localhost:3000/acme/shop/production/api?tab=builds&build=${testBuild.id}",""" +
                    """"description":"Build failed: the build job failed","context":"liftgate/api"}""",
            ),
            sent(1),
        )
    }

    @Test
    fun `the status follows the build and then its latest deployment`() = runBlocking {
        val succeeded = testBuild.copy(status = BuildStatus.SUCCEEDED)
        fun deployment(status: DeploymentStatus, error: String? = null) = testDeployment.copy(buildId = testBuild.id, status = status, error = error)
        assertEquals("pending" to "Queued", post(testBuild.copy(status = BuildStatus.QUEUED)))
        assertEquals("pending" to "Building", post(testBuild.copy(status = BuildStatus.RUNNING)))
        assertEquals("error" to "Cancelled by a newer push", post(testBuild.copy(status = BuildStatus.CANCELLED)))
        assertEquals("success" to "Built, and a newer commit was deployed", post(succeeded))
        assertEquals("pending" to "Deploying", post(succeeded, deployment(DeploymentStatus.RELEASING)))
        assertEquals("success" to "Deployed", post(succeeded, deployment(DeploymentStatus.RUNNING)))
        assertEquals("failure" to "Deployment failed: crash loop", post(succeeded, deployment(DeploymentStatus.FAILED, "crash loop")))
        assertEquals(140, post(testBuild.copy(status = BuildStatus.FAILED, error = "x".repeat(500))).second.length)
    }

    @Test
    fun `a build that finishes while its pending status is posted ends on the final status`() = runBlocking {
        build = testBuild.copy(status = BuildStatus.QUEUED)
        onStatus = { build = testBuild.copy(status = BuildStatus.FAILED, error = "the build job failed") }
        CommitStatuses(app).post(testBuild.id)
        assertEquals(listOf("pending", "failure"), listOf(1, 3).map { sent(it).jsonObject.getValue("state").jsonPrimitive.content })
        assertEquals(4, requests.size)
    }

    @Test
    fun `github refusing a status drops it, while rate limits and server errors are retried`() = runBlocking {
        answer = HttpStatusCode.UnprocessableEntity
        CommitStatuses(app).post(testBuild.id)
        answer = HttpStatusCode.Forbidden
        CommitStatuses(app).post(testBuild.id)
        rateLimited = true
        assertFailsWith<ClientRequestException> { CommitStatuses(app).post(testBuild.id) }
        rateLimited = false
        answer = HttpStatusCode.TooManyRequests
        assertFailsWith<ClientRequestException> { CommitStatuses(app).post(testBuild.id) }
        answer = HttpStatusCode.BadGateway
        assertFailsWith<ServerResponseException> { CommitStatuses(app).post(testBuild.id) }
        assertEquals(10, requests.size)
    }
}
