package dev.liftgate.build

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.auth.Access
import dev.liftgate.auth.Sessions
import dev.liftgate.cache.Cache
import dev.liftgate.config.GitHubConfig
import dev.liftgate.db.Builds as BuildsTable
import dev.liftgate.db.Housekeeping
import dev.liftgate.db.PullRequests
import dev.liftgate.db.now
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Builds
import dev.liftgate.domain.Domains
import dev.liftgate.http.ErrorBody
import dev.liftgate.http.WEBHOOKS_PER_MINUTE
import dev.liftgate.http.json
import dev.liftgate.http.liftgate
import dev.liftgate.http.session
import dev.liftgate.org.Limits
import dev.liftgate.org.Orgs
import dev.liftgate.org.Plan
import dev.liftgate.org.Plans
import dev.liftgate.org.insertUser
import dev.liftgate.project.Environment
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.PreviewSettings
import dev.liftgate.project.Projects
import dev.liftgate.project.PullRequest
import dev.liftgate.secret.SecretBox
import dev.liftgate.service.EnvVar
import dev.liftgate.service.EnvVars
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.service.Volume
import dev.liftgate.teardowns
import dev.liftgate.testConfig
import dev.liftgate.waitingLocks
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * @author Dean
 * @date 9/30/2026
 */
class PreviewsTest {
    private val secret = "It's a Secret to Everybody"
    private val db = TestDatabase.clean()
    private val user = runBlocking { db.tx { insertUser("dean", null, null, null) } }
    private val limits = Limits(Plans(mapOf("free" to Plan(previewEnvironments = 1)), "free"))
    private val projects = Projects(db, limits)
    private val services = Services(db, limits)
    private val builds = Builds(db)
    private val envVars = EnvVars(db, SecretBox(ByteArray(32)))
    private val requests = mutableListOf<HttpRequestData>()
    private var issues = "write"
    private val cache = mockk<Cache> { every { allow(any(), any(), any()) } returns true }
    private val github = GitHubApp(
        GitHubConfig("1", TestKeys.privateKeyPem),
        HttpClient(MockEngine { request ->
            requests += request
            val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            val body = (request.body as? TextContent)?.text.orEmpty()
            when (request.method to request.url.encodedPath) {
                HttpMethod.Post to "/app/installations/42/access_tokens" ->
                    if ("issues" in body && issues != "write") respond("""{"message":"The permissions requested are not granted to this installation."}""", HttpStatusCode.UnprocessableEntity, headers)
                    else respond("""{"token":"ghs_preview"}""", HttpStatusCode.Created, headers)
                HttpMethod.Get to "/app/installations/42" -> respond("""{"id":42,"permissions":{"contents":"read","issues":"$issues"},"events":["push"]}""", HttpStatusCode.OK, headers)
                HttpMethod.Get to "/repos/acme/shop/collaborators/dean/permission" -> respond("""{"permission":"write"}""", HttpStatusCode.OK, headers)
                HttpMethod.Post to "/repos/acme/shop/issues/12/comments" -> respond("""{"id":7}""", HttpStatusCode.Created, headers)
                HttpMethod.Patch to "/repos/acme/shop/issues/comments/7" -> respond("""{"id":7}""", HttpStatusCode.OK, headers)
                else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, headers)
            }
        }) { install(ContentNegotiation) { json(json) } },
    )
    private val app: App = mockk {
        every { config } returns testConfig().copy(githubWebhookSecret = secret)
        every { cache } returns this@PreviewsTest.cache
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { db } returns this@PreviewsTest.db
        every { sessions } returns mockk<Sessions> { coEvery { resolve("s") } returns user }
        every { access } returns mockk<Access>(relaxUnitFun = true)
        every { projects } returns this@PreviewsTest.projects
        every { services } returns this@PreviewsTest.services
        every { builds } returns this@PreviewsTest.builds
        every { envVars } returns this@PreviewsTest.envVars
        every { domains } returns Domains(this@PreviewsTest.db, "liftgate.app", limits)
        every { github } returns this@PreviewsTest.github
        every { previews } returns Previews(this)
    }
    private val org = runBlocking { Orgs(db).create("acme", "Acme", user.id) }
    private val project = runBlocking {
        projects.create(org.id, "shop", "Shop", "acme/shop", 42, "dean").let { projects.update(it.id, PreviewSettings(previewsEnabled = true)) }
    }
    private val production = runBlocking { projects.environments(project.id).single() }
    private val web = runBlocking { services.create(production.id, ServiceSpec("web", "Web", ServiceKind.WEB, port = 3000, cpuMillis = 250)) }
    private val worker = runBlocking { services.create(production.id, ServiceSpec("worker", "Worker", ServiceKind.WORKER, startCommand = "node worker.js")) }
    private val pull = PullRequest(12, "Add checkout", "checkout", "abc123", false)

    init {
        runBlocking { envVars.replace(web.id, listOf(EnvVar("DATABASE_URL", "postgres://db", secret = true), EnvVar("MODE", "production"))) }
    }

    private fun event(action: String, number: Int = 12, sha: String = "abc123", headRepo: String = "acme/shop") =
        """{"action":"$action","number":$number,"pull_request":{"title":"Add checkout","head":{"ref":"checkout","sha":"$sha","repo":{"full_name":"$headRepo","fork":${headRepo != "acme/shop"}}}},""" +
            """"repository":{"full_name":"acme/shop"},"installation":{"id":42}}"""

    private suspend fun ApplicationTestBuilder.deliver(body: String, event: String = "pull_request") = client.post("/api/v1/webhooks/github") {
        header("X-GitHub-Event", event)
        header("X-Hub-Signature-256", Webhooks.sign(secret, body.toByteArray()))
        setBody(body)
    }

    private suspend fun preview(number: Int = 12): Environment? = projects.environments(project.id).singleOrNull { it.pullRequest == number }

    private suspend fun builtShas(environment: Environment) = services.forEnvironment(environment.id).associate { service ->
        service.slug to builds.forService(service.id).filter { it.status == BuildStatus.QUEUED }.map { it.commitSha }
    }

    private fun sent(method: HttpMethod, path: String) = requests.filter { it.method == method && it.url.encodedPath == path }.map { (it.body as TextContent).text }

    private fun CoroutineScope.hold(statements: JdbcTransaction.() -> Unit) {
        val held = CountDownLatch(1)
        launch(Dispatchers.IO) {
            db.tx {
                statements()
                held.countDown()
                repeat(200) { if (waitingLocks() != 0L) return@tx; Thread.sleep(50) }
            }
        }
        held.await(10, TimeUnit.SECONDS)
    }

    @Test
    fun `an opened pull request creates pr-12 with the cloned services and variables, queues builds for head sha and comments its urls`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.NoContent, deliver(event("opened")).status)
        val environment = requireNotNull(preview())
        assertEquals(listOf("pr-12", "PR #12", "checkout"), listOf(environment.slug, environment.name, environment.branch))
        assertEquals(EnvironmentKind.PREVIEW, environment.kind)
        val copies = services.forEnvironment(environment.id)
        assertEquals(listOf(web.spec(), worker.spec()), copies.map { it.spec() })
        val copy = copies.single { it.slug == "web" }
        assertEquals(envVars.list(web.id, reveal = true), envVars.list(copy.id, reveal = true))
        assertEquals(mapOf("web" to listOf("abc123"), "worker" to listOf("abc123")), builtShas(environment))
        assertEquals(listOf("web-pr-12-shop-acme.liftgate.app"), app.domains.forService(copy.id).map { it.hostname })
        val comment = json.parseToJsonElement(sent(HttpMethod.Post, "/repos/acme/shop/issues/12/comments").single()).jsonObject.getValue("body").jsonPrimitive.content
        assertTrue("| web | https://web-pr-12-shop-acme.liftgate.app | not yet |" in comment, comment)
        assertEquals(7L, db.tx { PullRequests.selectAll().single()[PullRequests.commentId] })
    }

    @Test
    fun `synchronize queues new builds and updates the one comment, while a push to the pull request's branch builds nothing`() = testApplication {
        application { liftgate(app) }
        deliver(event("opened"))
        val environment = requireNotNull(preview())
        val push = """{"ref":"refs/heads/checkout","after":"def456","commits":[],"repository":{"full_name":"acme/shop"},"installation":{"id":42}}"""
        assertEquals(HttpStatusCode.NoContent, deliver(push, "push").status)
        assertEquals(mapOf("web" to listOf("abc123"), "worker" to listOf("abc123")), builtShas(environment))
        assertEquals(HttpStatusCode.NoContent, deliver(event("synchronize", sha = "def456")).status)
        assertEquals(mapOf("web" to listOf("def456"), "worker" to listOf("def456")), builtShas(environment))
        assertEquals(environment, preview())
        assertEquals(1, sent(HttpMethod.Post, "/repos/acme/shop/issues/12/comments").size)
        assertTrue("of `def456`" in sent(HttpMethod.Patch, "/repos/acme/shop/issues/comments/7").single())
    }

    @Test
    fun `closed tears down the namespace and deletes the preview`() = testApplication {
        application { liftgate(app) }
        deliver(event("opened"))
        val environment = requireNotNull(preview())
        assertEquals(HttpStatusCode.NoContent, deliver(event("closed")).status)
        assertNull(preview())
        assertEquals(listOf(environment.namespace), db.teardowns())
        assertEquals(0L, db.tx { PullRequests.selectAll().count() })
        assertTrue("removed" in sent(HttpMethod.Patch, "/repos/acme/shop/issues/comments/7").single())
    }

    @Test
    fun `a deploy waits for a close that holds the pull request row and then finds the pull request gone`() = runBlocking {
        app.previews.open(project, pull) { hold { removePreview(project.id, 12) } }
        assertNull(preview())
        assertEquals(0L, db.tx { PullRequests.selectAll().count() })
    }

    @Test
    fun `a close waits for an approval's deploy that holds the pull request row and then removes the environment it created`() = runBlocking {
        app.previews.open(project, pull.copy(fork = true)) {}
        hold {
            PullRequests.selectAll().where { (PullRequests.projectId eq project.id) and (PullRequests.number eq 12) }.forUpdate().toList()
            projects.insertPreview(project.id, 12, "checkout")
        }
        app.previews.close(project, 12) {}
        assertNull(preview())
        assertEquals(1, db.teardowns().size)
    }

    @Test
    fun `the idle sweep waits for a delivery that holds the pull request row and keeps the preview it refreshed`() = runBlocking {
        app.previews.open(project, pull) {}
        val environment = requireNotNull(preview())
        db.tx { PullRequests.update { it[updatedAt] = now().minusDays(15) } }
        hold { PullRequests.update { it[updatedAt] = now() } }
        Housekeeping(db).runOnce()
        assertEquals(environment, preview())
        assertEquals(emptyList(), db.teardowns())
    }

    @Test
    fun `a fork pull request creates nothing until an admin approves its head commit, and a later push waits for approval again`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.NoContent, deliver(event("opened", headRepo = "mallory/shop")).status)
        assertNull(preview())
        assertEquals(0L, db.tx { BuildsTable.selectAll().count() })
        assertEquals(emptyList(), requests.filter { it.method == HttpMethod.Post && "comments" in it.url.encodedPath })
        suspend fun approve(sha: String) = client.post("/api/v1/projects/${project.id}/previews/approve") {
            session()
            contentType(ContentType.Application.Json)
            setBody("""{"number":12,"sha":"$sha"}""")
        }
        assertEquals(HttpStatusCode.Conflict, approve("0ld5ha").status)
        assertNull(preview())
        assertEquals(HttpStatusCode.NoContent, approve("abc123").status)
        val environment = requireNotNull(preview())
        assertEquals(mapOf("web" to listOf("abc123"), "worker" to listOf("abc123")), builtShas(environment))
        deliver(event("synchronize", sha = "def456", headRepo = "mallory/shop"))
        assertEquals(mapOf("web" to listOf("abc123"), "worker" to listOf("abc123")), builtShas(environment))
        val listed = client.get("/api/v1/projects/${project.id}/previews") { session() }.bodyAsText()
        assertTrue(""""headSha":"def456","fork":true,"approvedSha":"abc123"""" in listed, listed)
    }

    @Test
    fun `pull request events spend their head repository's own webhook limit, and only when there is work to do`() = testApplication {
        application { liftgate(app) }
        listOf(event("closed", headRepo = "mallory/shop"), event("opened", headRepo = "mallory/shop"), event("synchronize", sha = "def456", headRepo = "mallory/shop")).forEach {
            assertEquals(HttpStatusCode.NoContent, deliver(it).status)
        }
        verify(exactly = 0) { cache.allow(any(), any(), any()) }
        deliver(event("closed", headRepo = "mallory/shop"))
        deliver(event("opened"))
        verify(exactly = 1) { cache.allow("rate:webhook-pull-request:42:mallory/shop", WEBHOOKS_PER_MINUTE, 1.minutes) }
        verify(exactly = 1) { cache.allow("rate:webhook-pull-request:42:acme/shop", WEBHOOKS_PER_MINUTE, 1.minutes) }
        verify(exactly = 2) { cache.allow(any(), any(), any()) }
    }

    @Test
    fun `the second preview on a free plan returns plan_limit and records why on the pull request`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.NoContent, deliver(event("opened")).status)
        val refused = deliver(event("opened", number = 13))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        val error = ErrorBody("plan_limit", "the free plan's preview environments limit is 1")
        assertEquals(error, json.decodeFromString(ErrorBody.serializer(), refused.bodyAsText()))
        assertNull(preview(13))
        assertEquals(error.message, db.tx { PullRequests.selectAll().where { PullRequests.number eq 13 }.single()[PullRequests.error] })
    }

    @Test
    fun `without a storage class a production service with a volume gets no preview`() = testApplication {
        services.update(web.id, web.spec().copy(volume = Volume("/data", 1)))
        application { liftgate(app) }
        val refused = deliver(event("opened"))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("storage_not_configured", json.decodeFromString(ErrorBody.serializer(), refused.bodyAsText()).error)
        assertNull(preview())
    }

    @Test
    fun `previews idle for 14 days are deleted`() = testApplication {
        application { liftgate(app) }
        deliver(event("opened"))
        val environment = requireNotNull(preview())
        Housekeeping(db).runOnce()
        assertEquals(environment, preview())
        db.tx { PullRequests.update { it[updatedAt] = now().minusDays(15) } }
        Housekeeping(db).runOnce()
        assertNull(preview())
        assertEquals(listOf(environment.namespace), db.teardowns())
    }

    @Test
    fun `without the pull request and issue settings the App still deploys previews, skips the comment and the project lists what is missing`() = testApplication {
        issues = "read"
        application { liftgate(app) }
        assertEquals(HttpStatusCode.NoContent, deliver(event("opened")).status)
        assertEquals(mapOf("web" to listOf("abc123"), "worker" to listOf("abc123")), builtShas(requireNotNull(preview())))
        assertEquals(emptyList(), sent(HttpMethod.Post, "/repos/acme/shop/issues/12/comments"))
        val status = json.parseToJsonElement(client.get("/api/v1/projects/${project.id}/previews") { session() }.bodyAsText()).jsonObject
        assertEquals(
            json.parseToJsonElement("""["Subscribe to events: Pull request","Pull requests: Read-only","Issues: Read and write"]"""),
            status.getValue("missing"),
        )
    }
}
