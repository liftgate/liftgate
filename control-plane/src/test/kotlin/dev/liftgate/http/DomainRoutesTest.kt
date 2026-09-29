package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.cache.Cache
import dev.liftgate.domain.Domains
import dev.liftgate.testConfig
import dev.liftgate.unlimitedCache
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Dean
 * @date 9/27/2026
 */
class DomainRoutesTest {
    private val domains = mockk<Domains> {
        coEvery { allowed(any()) } returns false
        coEvery { allowed("shop.example.com") } returns true
    }
    private val app = mockk<App> {
        every { this@mockk.domains } returns this@DomainRoutesTest.domains
        every { config } returns testConfig()
        every { cache } returns unlimitedCache
        every { metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    }

    @Test
    fun `the ask endpoint answers 200 for a verified host and 404 otherwise without a session`() = testApplication {
        application { liftgate(app) }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/domains/allowed?domain=shop.example.com").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/domains/allowed?domain=other.example.com").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/domains/allowed").status)
    }

    @Test
    fun `the ask endpoint is rate limited per client address`() = testApplication {
        every { app.cache } returns mockk<Cache> { every { allow(match { it.startsWith("rate:domain-checks:") }, DOMAIN_CHECKS_PER_MINUTE, any()) } returns false }
        application { liftgate(app) }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/v1/domains/allowed?domain=shop.example.com").status)
        coVerify(exactly = 0) { domains.allowed(any()) }
    }
}
