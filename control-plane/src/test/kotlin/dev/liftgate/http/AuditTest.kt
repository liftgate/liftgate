package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.auth.Access
import dev.liftgate.auth.ApiTokens
import dev.liftgate.auth.GitConnections
import dev.liftgate.auth.OrgRole
import dev.liftgate.auth.Sessions
import dev.liftgate.auth.Sso
import dev.liftgate.auth.SsoSettings
import dev.liftgate.build.GitHubApp
import dev.liftgate.db.AuditLog
import dev.liftgate.db.Memberships
import dev.liftgate.db.sql
import dev.liftgate.deploy.Build
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Builds
import dev.liftgate.deploy.Deployment
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.Deployments
import dev.liftgate.domain.Domain
import dev.liftgate.domain.DomainKind
import dev.liftgate.domain.Domains
import dev.liftgate.org.AuditEntry
import dev.liftgate.org.Invitations
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Environment
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.Project
import dev.liftgate.project.Projects
import dev.liftgate.service.BuildStrategy
import dev.liftgate.service.EnvVar
import dev.liftgate.service.EnvVars
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.path
import io.ktor.server.routing.routingRoot
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/27/2026
 */
class AuditTest {
    companion object {
        @JvmStatic
        fun routes() = listOf(
            "POST /orgs",
            "DELETE /orgs/{slug}",
            "DELETE /orgs/{slug}/members/me",
            "PATCH /orgs/{slug}/members/{userId}",
            "DELETE /orgs/{slug}/members/{userId}",
            "POST /orgs/{slug}/invitations",
            "POST /orgs/{slug}/tokens",
            "DELETE /orgs/{slug}/tokens/{id}",
            "POST /orgs/{slug}/projects",
            "PUT /orgs/{slug}/sso",
            "DELETE /orgs/{slug}/sso",
            "POST /orgs/{slug}/sso/verify",
            "DELETE /projects/{id}",
            "POST /projects/{id}/environments",
            "POST /environments/{id}/services",
            "PATCH /services/{id}",
            "DELETE /services/{id}",
            "PUT /services/{id}/env",
            "POST /services/{id}/deploy",
            "POST /services/{id}/domains",
            "POST /deployments/{id}/rollback",
            "POST /domains/{id}/verify",
            "DELETE /domains/{id}",
            "POST /invitations/{token}/accept",
        )
    }

    private class Call(val path: String, val body: String? = null, val session: String = "owner", val expected: suspend () -> Triple<UUID?, String, String>)

    private val db = TestDatabase.clean()
    private val orgs = Orgs(db)
    private val sessions = mockk<Sessions>()
    private val apiTokens = ApiTokens(db, mockk(relaxed = true))
    private val invitations = Invitations(db, null, "https://dashboard.liftgate.test")
    private val users = listOf("owner", "admin", "member", "bob", "newbie").associateWith { runBlocking { db.tx { insertUser(it, null, null, null) }.id } }
    private val acme = runBlocking { orgs.create("acme", "Acme", users.getValue("owner")) }
    private val sha = "a".repeat(40)
    private val project = Project(UUID.randomUUID(), acme.id, "shop", "Shop", "acme/shop", "main", 42)
    private val environment = Environment(UUID.randomUUID(), project.id, "production", "Production", EnvironmentKind.PRODUCTION, "main", "env-0123456789ab")
    private val service = Service(UUID.randomUUID(), environment.id, "api", "API", ServiceKind.WORKER, "/", BuildStrategy.AUTO, "Dockerfile", null, 1, 500, 512, null, null)
    private val build = Build(UUID.randomUUID(), service.id, sha, null, "main", BuildStatus.SUCCEEDED, null, null, null, null, Instant.now())
    private val deployment = Deployment(UUID.randomUUID(), service.id, build.id, DeploymentStatus.RUNNING, 1, null, Instant.now())
    private val domain = Domain(UUID.randomUUID(), service.id, "shop.acme.dev", DomainKind.CUSTOM, "token", null, "pending")
    private val sso = SsoSettings("https://idp.acme.dev", "https://idp.acme.dev/sso", "certificate", listOf("acme.dev"))
    private val app = mockk<App>().also {
        every { it.config } returns testConfig()
        every { it.cache } returns unlimitedCache
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.db } returns db
        every { it.orgs } returns orgs
        every { it.access } returns Access(orgs)
        every { it.sessions } returns sessions
        every { it.apiTokens } returns apiTokens
        every { it.invitations } returns invitations
        every { it.projects } returns mockk<Projects> {
            coEvery { byId(project.id) } returns project
            coEvery { environment(environment.id) } returns environment
            coEvery { create(acme.id, "web", "Web", "acme/web", 42, "dean") } returns project
            coEvery { delete(project.id) } just Runs
            coEvery { createEnvironment(project.id, "staging", "Staging", EnvironmentKind.PRODUCTION, "dev") } returns environment
        }
        every { it.services } returns mockk<Services> {
            coEvery { scope(service.id) } returns ServiceScope(service, environment, project, acme)
            coEvery { create(environment.id, any()) } returns service
            coEvery { update(service.id, any()) } returns service
            coEvery { delete(service.id) } just Runs
        }
        every { it.envVars } returns mockk<EnvVars> {
            coEvery { replace(service.id, any()) } just Runs
            coEvery { list(service.id, false) } returns listOf(EnvVar("DATABASE_URL", null, true), EnvVar("PORT", "8080"))
        }
        every { it.builds } returns mockk<Builds> { coEvery { request(service.id, sha, null, "main") } returns build }
        every { it.deployments } returns mockk<Deployments> {
            coEvery { byId(deployment.id) } returns deployment
            coEvery { rollback(deployment.id) } returns deployment
        }
        every { it.domains } returns mockk<Domains> {
            coEvery { addCustom(service.id, "shop.acme.dev") } returns domain
            coEvery { byId(domain.id) } returns domain
            coEvery { verify(domain.id) } returns domain
            coEvery { delete(domain.id) } just Runs
        }
        every { it.sso } returns mockk<Sso> {
            coEvery { save(acme.id, any()) } returns sso
            coEvery { delete(acme.id) } just Runs
            coEvery { verifyDomains(acme.id) } returns sso
        }
        every { it.github } returns mockk<GitHubApp> { coEvery { installation("ghu_token", "acme/web") } returns 42 }
        every { it.gitConnections } returns mockk<GitConnections> { coEvery { github(users.getValue("owner")) } returns ("ghu_token" to "dean") }
    }

    init {
        runBlocking {
            db.tx {
                listOf("admin" to OrgRole.ADMIN, "member" to OrgRole.MEMBER, "bob" to OrgRole.MEMBER).forEach { (login, role) ->
                    Memberships.insert {
                        it[orgId] = acme.id
                        it[userId] = users.getValue(login)
                        it[Memberships.role] = role.sql
                    }
                }
            }
        }
        users.forEach { (login, id) -> coEvery { sessions.resolve(login) } coAnswers { orgs.user(id) } }
    }

    private val token = runBlocking { apiTokens.create(acme.id, "ci", users.getValue("owner"), null) }
    private val tokenId = runBlocking { apiTokens.list(acme.id).single().id }
    private val invitation = runBlocking { invitations.create(acme, checkNotNull(orgs.user(users.getValue("owner"))), OrgRole.MEMBER, null).url.substringAfterLast('/') }

    private fun acme(type: String, id: Any): suspend () -> Triple<UUID?, String, String> = { Triple(acme.id, type, id.toString()) }

    private val calls = mapOf(
        "POST /orgs" to Call("/orgs", """{"slug":"beta","name":"Beta"}""") { orgs.bySlug("beta")?.id.let { Triple(it, "orgs", it.toString()) } },
        "DELETE /orgs/{slug}" to Call("/orgs/acme") { Triple(null, "orgs", "acme") },
        "DELETE /orgs/{slug}/members/me" to Call("/orgs/acme/members/me", session = "member", expected = acme("orgs", "acme")),
        "PATCH /orgs/{slug}/members/{userId}" to Call("/orgs/acme/members/${users["bob"]}", """{"role":"admin"}""", expected = acme("members", users.getValue("bob"))),
        "DELETE /orgs/{slug}/members/{userId}" to Call("/orgs/acme/members/${users["bob"]}", expected = acme("members", users.getValue("bob"))),
        "POST /orgs/{slug}/invitations" to Call("/orgs/acme/invitations", """{"role":"member"}""", expected = acme("orgs", "acme")),
        "POST /orgs/{slug}/tokens" to Call("/orgs/acme/tokens", """{"name":"deploy"}""", expected = acme("orgs", "acme")),
        "DELETE /orgs/{slug}/tokens/{id}" to Call("/orgs/acme/tokens/$tokenId", expected = acme("tokens", tokenId)),
        "POST /orgs/{slug}/projects" to Call("/orgs/acme/projects", """{"slug":"web","name":"Web","repoFullName":"acme/web"}""", expected = acme("orgs", "acme")),
        "PUT /orgs/{slug}/sso" to Call("/orgs/acme/sso", json.encodeToString(SsoSettings.serializer(), sso), expected = acme("orgs", "acme")),
        "DELETE /orgs/{slug}/sso" to Call("/orgs/acme/sso", expected = acme("orgs", "acme")),
        "POST /orgs/{slug}/sso/verify" to Call("/orgs/acme/sso/verify", expected = acme("orgs", "acme")),
        "DELETE /projects/{id}" to Call("/projects/${project.id}", expected = acme("projects", project.id)),
        "POST /projects/{id}/environments" to Call("/projects/${project.id}/environments", """{"slug":"staging","name":"Staging","branch":"dev"}""", expected = acme("projects", project.id)),
        "POST /environments/{id}/services" to Call("/environments/${environment.id}/services", """{"slug":"api","name":"API","kind":"worker"}""", expected = acme("environments", environment.id)),
        "PATCH /services/{id}" to Call("/services/${service.id}", """{"replicas":2}""", expected = acme("services", service.id)),
        "DELETE /services/{id}" to Call("/services/${service.id}", expected = acme("services", service.id)),
        "PUT /services/{id}/env" to Call("/services/${service.id}/env", """[{"name":"DATABASE_URL","value":"postgres://secret-value","secret":true}]""", expected = acme("services", service.id)),
        "POST /services/{id}/deploy" to Call("/services/${service.id}/deploy", """{"ref":"$sha"}""", expected = acme("services", service.id)),
        "POST /services/{id}/domains" to Call("/services/${service.id}/domains", """{"hostname":"shop.acme.dev"}""", expected = acme("services", service.id)),
        "POST /deployments/{id}/rollback" to Call("/deployments/${deployment.id}/rollback", expected = acme("deployments", deployment.id)),
        "POST /domains/{id}/verify" to Call("/domains/${domain.id}/verify", expected = acme("domains", domain.id)),
        "DELETE /domains/{id}" to Call("/domains/${domain.id}", expected = acme("domains", domain.id)),
        "POST /invitations/{token}/accept" to Call("/invitations/$invitation/accept", session = "newbie", expected = acme("orgs", acme.id)),
    )

    private suspend fun rows() = db.tx {
        AuditLog.selectAll().map { listOf(it[AuditLog.orgId], it[AuditLog.actorUserId], it[AuditLog.action], it[AuditLog.targetType], it[AuditLog.targetId]) }
    }

    private suspend fun HttpClient.putEnv() = put("/api/v1/services/${service.id}/env") {
        session("owner")
        contentType(ContentType.Application.Json)
        setBody("""[{"name":"DATABASE_URL","value":"postgres://secret-value","secret":true},{"name":"PORT","value":"8080"}]""")
    }

    @ParameterizedTest
    @MethodSource("routes")
    fun `a mutating route writes one audit row with its org, actor and target`(route: String) = testApplication {
        application { liftgate(app) }
        val case = calls.getValue(route)
        val response = client.request("/api/v1${case.path}") {
            method = HttpMethod.parse(route.substringBefore(' '))
            session(case.session)
            case.body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        assertTrue(response.status.isSuccess(), "$route answered ${response.status}: ${response.bodyAsText()}")
        val (org, type, id) = case.expected()
        assertEquals(listOf(listOf(org, users.getValue(case.session), route, type, id)), rows())
    }

    @Test
    fun `the table covers every mutating route under the org-scoped paths`() = testApplication {
        lateinit var root: RoutingNode
        application {
            liftgate(app)
            root = routingRoot
        }
        startApplication()
        val mutating = root.getAllRoutes().mapNotNull { route ->
            (route.selector as? HttpMethodRouteSelector)?.method?.takeIf { it !in safeMethods }?.let { "${it.value} ${route.path.removePrefix("/api/v1")}" }
        }
        val scoped = mutating.filter { route -> orgScopedPaths.any { route.substringAfter(' ').startsWith(it) } }
        assertEquals(routes().sorted(), scoped.sorted())
        assertEquals(routes().toSet(), calls.keys)
    }

    @Test
    fun `failed and read-only requests write nothing`() = testApplication {
        application { liftgate(app) }
        client.putEnv()
        val refused = client.put("/api/v1/services/${service.id}/env") {
            session("member")
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/services/${service.id}/env") { session("owner") }.status)
        assertEquals(1, rows().size)
    }

    @Test
    fun `an env var row names the variables and never holds a value`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.OK, client.putEnv().status)
        val row = db.tx { AuditLog.selectAll().single().let { row -> AuditLog.columns.map { row[it] } } }
        assertTrue("DATABASE_URL,PORT" in row.toString())
        assertFalse("secret-value" in row.toString() || "8080" in row.toString())
    }

    @Test
    fun `admins read the audit log newest first a page at a time and members cannot`() = testApplication {
        application { liftgate(app) }
        repeat(3) { client.putEnv() }
        client.post("/api/v1/services/${service.id}/deploy") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"ref":"$sha"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/orgs/acme/audit") { session("member") }.status)
        suspend fun page(query: String) = json.decodeFromString(ListSerializer(AuditEntry.serializer()), client.get("/api/v1/orgs/acme/audit?$query") { session("admin") }.bodyAsText())
        val first = page("limit=2")
        assertEquals(listOf("POST /services/{id}/deploy" to true, "PUT /services/{id}/env" to false), first.map { it.action to it.viaToken })
        assertEquals(listOf("owner", "owner"), first.map { it.actor?.login })
        val rest = page("before=${first.last().id}")
        assertEquals(2, rest.size)
        assertTrue(rest.all { it.id < first.last().id && it.details.getValue("names").jsonPrimitive.content == "DATABASE_URL,PORT" })
        assertEquals(HttpStatusCode.UnprocessableEntity, client.get("/api/v1/orgs/acme/audit?limit=500") { session("admin") }.status)
    }
}
