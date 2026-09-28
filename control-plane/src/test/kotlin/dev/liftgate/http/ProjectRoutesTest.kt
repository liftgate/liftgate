package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.auth.Access
import dev.liftgate.auth.GitConnections
import dev.liftgate.auth.Sessions
import dev.liftgate.build.GitHubApp
import dev.liftgate.db.Projects as ProjectsTable
import dev.liftgate.org.Limits
import dev.liftgate.org.Orgs
import dev.liftgate.org.Plan
import dev.liftgate.org.Plans
import dev.liftgate.org.insertUser
import dev.liftgate.project.Project
import dev.liftgate.project.Projects
import dev.liftgate.discardingDb
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
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
        every { github } returns mockk<GitHubApp> { coEvery { installation("ghu_dean", any()) } returns 42 }
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
