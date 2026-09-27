package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.Sessions
import dev.liftgate.org.Orgs
import dev.liftgate.org.User
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Dean
 * @date 9/27/2026
 */
class OrgRoutesTest {
    private val orgs = mockk<Orgs>()
    private val app = mockk<App>().also {
        every { it.orgs } returns orgs
        every { it.sessions } returns mockk<Sessions> { coEvery { resolve("s") } returns User(UUID.randomUUID(), "dean", null, null, null) }
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.config } returns testConfig()
        every { it.cache } returns unlimitedCache
    }

    @Test
    fun `reserved slugs cannot name an organization`() = testApplication {
        application { liftgate(app) }
        listOf("docs", "new", "settings", "admin", "status", "www", "app", "dashboard", "login").forEach {
            val response = client.post("/api/v1/orgs") {
                session()
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"$it","name":"Taken"}""")
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, it)
        }
        coVerify(exactly = 0) { orgs.create(any(), any(), any()) }
    }
}
