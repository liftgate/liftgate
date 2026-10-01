package dev.liftgate.database

import dev.liftgate.TestDatabase
import dev.liftgate.db.Outbox
import dev.liftgate.events.Subject
import dev.liftgate.http.LiftgateException
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Environment
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * @author Dean
 * @date 9/30/2026
 */
class DatabasesTest {
    private val db = TestDatabase.clean()
    private val databases = Databases(db)
    private val services = Services(db)

    private suspend fun environment(): Environment {
        val org = Orgs(db).create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id)
        val projects = Projects(db)
        return projects.environments(projects.create(org.id, "shop", "Shop", "acme/shop", 42).id).single()
    }

    private fun spec(slug: String) = ServiceSpec(slug, slug, ServiceKind.WEB)

    @Test
    fun `a database is requested in its namespace, lists its links and stays while a service uses it`() = runBlocking {
        val environment = environment()
        val web = services.create(environment.id, spec("web"))
        val main = databases.create(environment.id, DatabaseSpec("main"))
        databases.link(main.id, ServiceLink(web.id))
        databases.link(main.id, ServiceLink(web.id, "READ_URL"))
        assertEquals(listOf(ServiceLink(web.id, "DATABASE_URL"), ServiceLink(web.id, "READ_URL")), databases.forEnvironment(environment.id).single().links.sortedBy { it.envName })
        assertEquals(mapOf("DATABASE_URL" to "main", "READ_URL" to "main"), databases.links(web.id))

        val refused = assertFailsWith<LiftgateException> { databases.delete(main.id) }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        databases.unlink(main.id, web.id)
        databases.delete(main.id)
        assertEquals(emptyList(), databases.forEnvironment(environment.id))

        val request = buildJsonObject { put("namespace", environment.namespace); put("databaseId", main.id.toString()) }
        assertEquals(listOf(request, request), db.tx { Outbox.selectAll().where { Outbox.subject eq Subject.DATABASE_REQUESTED.value }.map { it[Outbox.payload] } })
    }

    @Test
    fun `a database and a service cannot take each other's kubernetes service names`() = runBlocking {
        val environment = environment()
        services.create(environment.id, spec("cache-rw"))
        assertEquals("slug", assertFailsWith<LiftgateException> { databases.create(environment.id, DatabaseSpec("cache")) }.field)
        databases.create(environment.id, DatabaseSpec("main"))
        listOf("main-rw", "main-ro", "main-r", "main-any").forEach { assertEquals("slug", assertFailsWith<LiftgateException> { services.create(environment.id, spec(it)) }.field) }
        assertEquals(listOf("main-web", "main"), listOf("main-web", "main").map { services.create(environment.id, spec(it)).slug })
    }
}
