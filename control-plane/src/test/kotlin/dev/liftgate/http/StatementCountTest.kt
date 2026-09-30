package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.auth.Access
import dev.liftgate.auth.Sessions
import dev.liftgate.cache.Cache
import dev.liftgate.deploy.Builds
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Dean
 * @date 9/30/2026
 */
class StatementCountTest {
    companion object {
        private val cache by lazy { Cache(testConfig()) }

        @AfterAll
        @JvmStatic
        fun close() = cache.close()
    }

    private val db = TestDatabase.clean()
    private val orgs = Orgs(db)
    private val services = Services(db)
    private val builds = Builds(db)
    private val sessions by lazy { Sessions(db, cache, orgs) }
    private val app = mockk<App>().also {
        every { it.config } returns testConfig()
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.db } returns db
        every { it.orgs } returns orgs
        every { it.access } returns Access(orgs)
        every { it.sessions } returns sessions
        every { it.services } returns services
        every { it.builds } returns builds
    }

    @Test
    fun `a member lists the builds of a service in three statements`() = testApplication {
        val member = db.tx { insertUser("dean", null, null, null) }
        val environment = Projects(db).let { it.environments(it.create(orgs.create("acme", "Acme", member.id).id, "shop", "Shop", "acme/shop", 42).id).single() }
        val service = services.create(environment.id, ServiceSpec("web", "Web", ServiceKind.WEB))
        builds.request(service.id, "a".repeat(40), null, "main")
        val cookie = sessions.create(member.id)
        application { liftgate(app) }
        suspend fun list() = assertEquals(HttpStatusCode.OK, client.get("/api/v1/services/${service.id}/builds") { session(cookie) }.status)
        list()
        db.tx { exec("create extension if not exists pg_stat_statements"); exec("select pg_stat_statements_reset()") { } }
        list()
        val statements = db.tx {
            exec("select query, calls from pg_stat_statements where query not like '%pg_stat_statements%'") {
                generateSequence { if (it.next()) it.getString(1) to it.getLong(2) else null }.toList()
            }
        }.orEmpty()
        assertEquals(3, statements.sumOf { it.second }, statements.joinToString("\n"))
    }
}
