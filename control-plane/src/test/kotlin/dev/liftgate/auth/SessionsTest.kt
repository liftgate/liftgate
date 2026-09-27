package dev.liftgate.auth

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.cache.Cache
import dev.liftgate.db.Sessions as SessionsTable
import dev.liftgate.http.SESSION_COOKIE
import dev.liftgate.http.liftgate
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.testConfig
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * @author Dean
 * @date 9/27/2026
 */
class SessionsTest {
    companion object {
        private val cache by lazy { Cache(testConfig()) }

        @AfterAll
        @JvmStatic
        fun close() = cache.close()
    }

    private val db = TestDatabase.clean()
    private val sessions by lazy { Sessions(db, cache, Orgs(db)) }

    @Test
    fun `only a hash of the cookie is stored or cached`() = runBlocking {
        val user = db.tx { insertUser("dean", null, null, null) }
        val cookie = sessions.create(user.id)
        val stored = db.tx { SessionsTable.selectAll().map { it[SessionsTable.id] } }
        assertEquals(listOf(ApiTokens.hash(cookie)), stored)
        assertFalse(cache.sessions.containsKey(cookie))
        assertEquals(user, sessions.resolve(cookie))
        assertNull(sessions.resolve(stored.single()))
    }

    @Test
    fun `signing out everywhere ends every session of that user only`() = testApplication {
        val app = mockk<App>().also {
            every { it.config } returns testConfig()
            every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            every { it.sessions } returns sessions
        }
        application { liftgate(app) }
        val (dean, other) = db.tx { insertUser("dean", null, null, null) to insertUser("other", null, null, null) }
        val mine = List(3) { sessions.create(dean.id) }
        val theirs = sessions.create(other.id)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/me/sessions") { cookie(SESSION_COOKIE, mine.first()) }.status)
        assertEquals(List(3) { HttpStatusCode.Unauthorized }, mine.map { session -> client.get("/api/v1/me") { cookie(SESSION_COOKIE, session) }.status })
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { cookie(SESSION_COOKIE, theirs) }.status)
    }
}
