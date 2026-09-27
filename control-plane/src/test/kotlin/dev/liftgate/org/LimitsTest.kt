package dev.liftgate.org

import dev.liftgate.TestDatabase
import dev.liftgate.db.Environments
import dev.liftgate.db.Organizations
import dev.liftgate.db.Projects as ProjectsTable
import dev.liftgate.db.Services as ServicesTable
import dev.liftgate.domain.Domains
import dev.liftgate.http.LiftgateException
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * @author Dean
 * @date 9/27/2026
 */
class LimitsTest {
    private val db = TestDatabase.clean()
    private val free = Plan(
        ownedOrgs = 1, projects = 1, environmentsPerProject = 2, services = 2, cpuMillis = 1000, memoryMb = 1024, replicas = 3, customDomains = 1,
    )
    private val limits = Limits(Plans(mapOf("free" to free, "unlimited" to Plan()), "free"), customDomainsMax = 2)
    private val orgs = Orgs(db, limits)
    private val projects = Projects(db, limits)
    private val services = Services(db, limits)
    private val domains = Domains(db, "liftgate.app", limits)
    private var installation = 0L

    private suspend fun refused(block: suspend () -> Any): String {
        val error = assertFailsWith<LiftgateException> { block() }
        assertEquals(HttpStatusCode.Conflict to "plan_limit", error.status to error.code)
        return error.message
    }

    private suspend fun rows(table: Table) = db.tx { table.selectAll().count() }

    private fun spec(slug: String, replicas: Int = 1, cpuMillis: Int = 500, memoryMb: Int = 512) = ServiceSpec(slug, slug, ServiceKind.WEB, replicas = replicas, cpuMillis = cpuMillis, memoryMb = memoryMb)

    private suspend fun production(org: String): UUID {
        val owner = db.tx { insertUser(org, null, null, null) }.id
        val project = projects.create(orgs.create(org, org, owner).id, "shop", "Shop", "$org/shop", ++installation)
        return projects.environments(project.id).single().id
    }

    @Test
    fun `on the free plan the next org, project, environment, service or domain is refused and creates no row`() = runBlocking {
        val owner = db.tx { insertUser("dean", null, null, null) }.id
        val org = orgs.create("acme", "Acme", owner)
        assertEquals("the free plan's organizations per owner limit is 1", refused { orgs.create("acme-two", "Acme Two", owner) })
        assertEquals(1L, rows(Organizations))

        val project = projects.create(org.id, "shop", "Shop", "acme/shop", 1)
        assertEquals("the free plan's projects limit is 1", refused { projects.create(org.id, "blog", "Blog", "acme/blog", 1) })
        assertEquals(1L, rows(ProjectsTable))

        projects.createEnvironment(project.id, "staging", "Staging", EnvironmentKind.PREVIEW, "develop")
        assertEquals("the free plan's environments per project limit is 2", refused { projects.createEnvironment(project.id, "qa", "QA", EnvironmentKind.PREVIEW, "qa") })
        assertEquals(2L, rows(Environments))

        val production = projects.environments(project.id).single { it.slug == "production" }.id
        val web = services.create(production, spec("web"))
        services.create(production, spec("api"))
        assertEquals("the free plan's services limit is 2", refused { services.create(production, spec("worker", cpuMillis = 1)) })
        assertEquals(2L, rows(ServicesTable))

        domains.addCustom(web.id, "app.acme.dev")
        assertEquals("the free plan's custom domains limit is 1", refused { domains.addCustom(web.id, "www.acme.dev") })
        assertEquals(1, domains.forService(web.id).size)
    }

    @Test
    fun `a patch over the org's cpu budget is refused and leaves the service unchanged, while shrinking always passes`() = runBlocking {
        val production = production("acme")
        val web = services.create(production, spec("web"))
        services.create(production, spec("api"))
        assertEquals(
            "the free plan's cpuMillis limit is 1000 across the organization, and this change needs 1100",
            refused { services.update(web.id, spec("web", cpuMillis = 600)) },
        )
        assertEquals(500, services.scope(web.id)?.service?.cpuMillis)
        assertEquals("the free plan's replicas limit is 3 across the organization, and this change needs 4", refused { services.update(web.id, spec("web", replicas = 3, cpuMillis = 1, memoryMb = 1)) })

        db.tx { Organizations.update({ Organizations.slug eq "acme" }) { it[plan] = "unlimited" } }
        services.update(web.id, spec("web", replicas = 2, cpuMillis = 1000))
        db.tx { Organizations.update({ Organizations.slug eq "acme" }) { it[plan] = DEFAULT_PLAN } }
        assertEquals(1000, services.update(web.id, spec("web", replicas = 1, cpuMillis = 1000)).cpuMillis)
        assertEquals("renamed", services.update(web.id, spec("web", cpuMillis = 1000).copy(name = "renamed")).name)
        assertEquals(
            "the free plan's cpuMillis limit is 1000 across the organization, and this change needs 2500",
            refused { services.update(web.id, spec("web", replicas = 2, cpuMillis = 1000)) },
        )
    }

    @Test
    fun `two concurrent service creates cannot both take the last slot`() = runBlocking {
        repeat(5) { round ->
            val production = production("org-$round")
            services.create(production, spec("web", cpuMillis = 100))
            val results = coroutineScope {
                listOf("api", "worker").map { async(Dispatchers.IO) { runCatching { services.create(production, spec(it, cpuMillis = 100)) } } }.awaitAll()
            }
            assertEquals(1, results.count { it.isSuccess }, "round $round")
            assertEquals("plan_limit", (results.single { it.isFailure }.exceptionOrNull() as LiftgateException).code)
        }
        assertEquals(10L, rows(ServicesTable))
    }

    @Test
    fun `the platform custom domain cap counts every org`() = runBlocking {
        val hosts = listOf("a", "b", "c").map { org -> services.create(production(org), spec("web")).id to "$org.example.dev" }
        hosts.take(2).forEach { (service, host) -> domains.addCustom(service, host) }
        val (service, host) = hosts.last()
        assertEquals("this installation allows 2 custom domains in total and all of them are in use", refused { domains.addCustom(service, host) })
    }

    @Test
    fun `usage counts the org's projects, services and domains and what its replicas reserve`() = runBlocking {
        val production = production("acme")
        val web = services.create(production, spec("web", replicas = 2, cpuMillis = 250, memoryMb = 256))
        services.create(production, spec("api"))
        domains.addCustom(web.id, "app.acme.dev")
        val orgId = requireNotNull(orgs.bySlug("acme")).id
        assertEquals(Usage("free", free, 1, 2, 1, 3, 1000, 1024), orgs.usage(orgId))
        db.tx { Organizations.update({ Organizations.id eq orgId }) { it[plan] = "unlimited" } }
        assertEquals(Usage("unlimited", Plan(), 1, 2, 1, 3, 1000, 1024), orgs.usage(orgId))
    }
}
