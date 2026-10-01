package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.auth.Access
import dev.liftgate.auth.GitConnections
import dev.liftgate.auth.Sessions
import dev.liftgate.build.Detection
import dev.liftgate.build.GitHubApp
import dev.liftgate.cache.Cache
import dev.liftgate.db.Memberships
import dev.liftgate.db.Projects as ProjectsTable
import dev.liftgate.org.Limits
import dev.liftgate.org.Orgs
import dev.liftgate.org.Plan
import dev.liftgate.org.Plans
import dev.liftgate.org.UserStatus
import dev.liftgate.org.insertUser
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.Project
import dev.liftgate.project.Projects
import dev.liftgate.discardingDb
import dev.liftgate.teardowns
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Dean
 * @date 9/27/2026
 */
class ProjectRoutesTest {
    private val db = TestDatabase.clean()
    private val user = runBlocking { db.tx { insertUser("dean", null, null, null) } }
    private val orgs = Orgs(db)
    private val app = mockk<App> {
        every { config } returns testConfig()
        every { cache } returns unlimitedCache
        every { db } returns discardingDb
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { sessions } returns mockk<Sessions> { coEvery { resolve("s") } returns user }
        every { access } returns mockk<Access>(relaxUnitFun = true)
        every { orgs } returns this@ProjectRoutesTest.orgs
        every { projects } returns Projects(this@ProjectRoutesTest.db)
        every { gitConnections } returns mockk<GitConnections> { coEvery { github(user.id) } returns ("ghu_dean" to "dean") }
        every { github } returns mockk<GitHubApp> { coEvery { installation("ghu_dean", any()) } returns (42L to "master") }
    }

    @Test
    fun `a second org imports from an installation another org already uses`() = testApplication {
        orgs.create("acme", "Acme", user.id)
        orgs.create("rival", "Rival", user.id)
        application { liftgate(app) }
        suspend fun import(org: String, repo: String) = client.post("/api/v1/orgs/$org/projects") {
            session()
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"shop","name":"Shop","repoFullName":"$repo"}""")
        }
        listOf("acme" to "acme/shop", "rival" to "acme/docs").forEach { (org, repo) ->
            val response = import(org, repo)
            assertEquals(HttpStatusCode.Created, response.status)
            val project = json.decodeFromString(Project.serializer(), response.bodyAsText())
            assertEquals(Triple(repo, 42L, "dean"), Triple(project.repoFullName, project.installationId, project.importedByLogin))
        }
    }

    @Test
    fun `production tracks the repository's default branch`() = testApplication {
        orgs.create("acme", "Acme", user.id)
        application { liftgate(app) }
        val response = client.post("/api/v1/orgs/acme/projects") {
            session()
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"legacy","name":"Legacy","repoFullName":"acme/legacy"}""")
        }
        val project = json.decodeFromString(Project.serializer(), response.bodyAsText())
        assertEquals("master", project.repoDefaultBranch)
        assertEquals(listOf("master"), app.projects.environments(project.id).map { it.branch })
    }

    @Test
    fun `an import past the plan's project limit answers 409 plan_limit and creates nothing`() = testApplication {
        every { app.projects } returns Projects(db, Limits(Plans(mapOf("free" to Plan(projects = 1)), "free")))
        orgs.create("acme", "Acme", user.id)
        application { liftgate(app) }
        suspend fun import(slug: String) = client.post("/api/v1/orgs/acme/projects") {
            session()
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"$slug","name":"Shop","repoFullName":"acme/$slug"}""")
        }
        assertEquals(HttpStatusCode.Created, import("shop").status)
        val refused = import("blog")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(ErrorBody("plan_limit", "the free plan's projects limit is 1"), json.decodeFromString(ErrorBody.serializer(), refused.bodyAsText()))
        assertEquals(1L, db.tx { ProjectsTable.selectAll().count() })
    }

    @Test
    fun `deleting an environment removes it and tears down its namespace`() = testApplication {
        val org = orgs.create("acme", "Acme", user.id)
        val project = app.projects.create(org.id, "shop", "Shop", "acme/shop", 42)
        val staging = app.projects.createEnvironment(project.id, "staging", "Staging", EnvironmentKind.PRODUCTION, "develop")
        application { liftgate(app) }
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/environments/${staging.id}") { session() }.status)
        assertEquals(listOf("production"), app.projects.environments(project.id).map { it.slug })
        assertEquals(listOf(staging.namespace), db.teardowns())
    }

    @Test
    fun `project settings turn previews on from a base environment of the same project, pull request slugs stay free for previews, and deleting the base environment clears it`() = testApplication {
        val org = orgs.create("acme", "Acme", user.id)
        val project = app.projects.create(org.id, "shop", "Shop", "acme/shop", 42)
        val other = app.projects.environments(app.projects.create(org.id, "blog", "Blog", "acme/blog", 42).id).single()
        val staging = app.projects.createEnvironment(project.id, "staging", "Staging", EnvironmentKind.PRODUCTION, "develop")
        application { liftgate(app) }
        suspend fun patch(body: String) = client.patch("/api/v1/projects/${project.id}") {
            session()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, patch("""{"previewBaseEnvironmentId":"${other.id}"}""").status)
        val taken = client.post("/api/v1/projects/${project.id}/environments") {
            session()
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"pr-7","name":"Seven","branch":"seven"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, taken.status)
        val updated = json.decodeFromString(Project.serializer(), patch("""{"previewsEnabled":true,"previewBaseEnvironmentId":"${staging.id}"}""").bodyAsText())
        assertEquals(true to staging.id, updated.previewsEnabled to updated.previewBaseEnvironmentId)
        assertEquals(updated, json.decodeFromString(Project.serializer(), patch("""{}""").bodyAsText()))
        client.delete("/api/v1/environments/${staging.id}") { session() }
        assertEquals(updated.copy(previewBaseEnvironmentId = null), app.projects.byId(project.id))
    }

    @Test
    fun `detecting a project's repository is for active org admins, reads its default branch, and reads nothing once the importer lost write access`() = testApplication {
        val org = orgs.create("acme", "Acme", user.id)
        val project = app.projects.create(org.id, "shop", "Shop", "acme/shop", 42, "alice", defaultBranch = "trunk")
        val grace = db.tx { insertUser("grace", null, null, null) }
        db.tx {
            Memberships.insert {
                it[orgId] = org.id
                it[userId] = grace.id
                it[role] = "member"
            }
        }
        every { app.access } returns Access(orgs)
        every { app.cache } returns mockk<Cache> {
            every { allow(any(), any(), any()) } returns true
            every { detections } returns HashMap()
        }
        every { app.sessions } returns mockk<Sessions> {
            coEvery { resolve("s") } returns user
            coEvery { resolve("m") } returns grace
            coEvery { resolve("p") } returns user.copy(status = UserStatus.PENDING)
        }
        val github = mockk<GitHubApp> {
            coEvery { installationToken(42, "shop", any()) } returns "ghs_token"
            coEvery { canPush("ghs_token", "acme/shop", "alice") } returns true
            coEvery { commit("ghs_token", "acme/shop", "trunk") } returns GitHubApp.Head("abc123", "ship it", "t1")
            coEvery { tree("ghs_token", "acme/shop", "t1") } returns (listOf("package.json") to false)
            coEvery { files("ghs_token", "acme/shop", "abc123", listOf("package.json")) } returns mapOf("package.json" to """{"devDependencies":{"vite":"8"}}""")
        }
        every { app.github } returns github
        application { liftgate(app) }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/projects/${project.id}/detect") { session("m") }.status)
        val pending = client.get("/api/v1/projects/${project.id}/detect") { session("p") }
        assertEquals(HttpStatusCode.Forbidden to "account_pending", pending.status to json.decodeFromString(ErrorBody.serializer(), pending.bodyAsText()).error)
        val response = client.get("/api/v1/projects/${project.id}/detect") { session() }
        assertEquals(HttpStatusCode.OK, response.status)
        val detection = json.decodeFromString(Detection.serializer(), response.bodyAsText())
        assertEquals(Triple("trunk", "vite", "shop"), Triple(detection.ref, detection.services.single().framework?.id, detection.services.single().spec.slug))
        coEvery { github.canPush("ghs_token", "acme/shop", "alice") } returns false
        val refused = client.get("/api/v1/projects/${project.id}/detect?ref=main") { session() }
        assertEquals(HttpStatusCode.OK, refused.status)
        val unread = json.decodeFromString(Detection.serializer(), refused.bodyAsText())
        assertEquals(Triple(null, emptyList(), listOf("The GitHub account that imported acme/shop no longer has write access, so nothing was read and builds will fail. Re-import the project.")), Triple(unread.commit, unread.services, unread.warnings))
        coVerify(exactly = 1) { github.commit(any(), any(), any()) }
        coVerify(exactly = 1) { github.tree(any(), any(), any()) }
        coVerify(exactly = 1) { github.files(any(), any(), any(), any()) }
    }

    @Test
    fun `import answers 409 while the GitHub App is not configured`() = testApplication {
        orgs.create("acme", "Acme", user.id)
        every { app.github } returns null
        application { liftgate(app) }
        val response = client.post("/api/v1/orgs/acme/projects") {
            session()
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"shop","name":"Shop","repoFullName":"acme/shop"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("""{"error":"conflict","message":"the GitHub App is not configured"}""", response.bodyAsText())
    }
}
