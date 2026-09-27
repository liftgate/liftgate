package dev.liftgate.domain

import dev.liftgate.TestDatabase
import dev.liftgate.db.Domains as DomainsTable
import dev.liftgate.db.now
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.Project
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insertReturning
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/27/2026
 */
class DomainsTest {
    private val db = TestDatabase.clean()
    private val projects = Projects(db)
    private val services = Services(db)
    private val domains = Domains(db, "liftgate.app")
    private val owner = runBlocking { db.tx { insertUser("dean", null, null, null) }.id }
    private var installation = 0L

    private suspend fun project(org: String, slug: String) = projects.create(Orgs(db).create(org, org, owner).id, slug, slug, "$org/$slug", ++installation)

    private suspend fun production(project: Project) = projects.environments(project.id).first { it.slug == "production" }.id

    private suspend fun staging(project: Project) = projects.createEnvironment(project.id, "staging", "Staging", EnvironmentKind.PREVIEW, "develop").id

    private suspend fun web(environmentId: UUID, slug: String = "api") = services.create(environmentId, ServiceSpec(slug, slug, ServiceKind.WEB))

    private suspend fun claim(environmentId: UUID, slug: String = "api") =
        domains.ensurePlatform(requireNotNull(services.scope(web(environmentId, slug).id))).hostname

    private fun suffixed(prefix: String) = Regex("""$prefix-[0-9a-z]{6}\.liftgate\.app""")

    @Test
    fun `the same web service gets distinct hostnames in production and staging`() = runBlocking {
        val shop = project("acme", "shop")
        assertEquals("api-shop-acme.liftgate.app", claim(production(shop)))
        assertEquals("api-staging-shop-acme.liftgate.app", claim(staging(shop)))
    }

    @Test
    fun `a readable name another org holds falls back to the suffix form`() = runBlocking {
        assertEquals("web-api-acme-corp.liftgate.app", claim(production(project("acme-corp", "api")), "web"))
        val second = claim(production(project("corp", "api-acme")), "web")
        assertTrue(suffixed("web-api-acme").matches(second), second)
    }

    @Test
    fun `an over-long readable name uses the suffix form`() = runBlocking {
        val hostname = claim(production(project("o".repeat(40), "shop")), "s".repeat(20))
        assertTrue(suffixed("s{20}-shop").matches(hostname), hostname)
    }

    @Test
    fun `an issued platform hostname is kept and its readable name stays taken`() = runBlocking {
        val shop = project("acme", "shop")
        val legacy = web(staging(shop))
        val issued = db.tx {
            DomainsTable.insertReturning {
                it[id] = UUID.randomUUID()
                it[serviceId] = legacy.id
                it[hostname] = "api-shop-acme.liftgate.app"
                it[kind] = "platform"
                it[verifiedAt] = now()
                it[certificateStatus] = "ready"
            }.single().toDomain()
        }
        assertEquals(issued, domains.ensurePlatform(requireNotNull(services.scope(legacy.id))))
        assertEquals(listOf(issued), domains.forService(legacy.id))
        val production = claim(production(shop))
        assertTrue(suffixed("api-shop").matches(production), production)
    }
}
