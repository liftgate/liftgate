package dev.liftgate.domain

import dev.liftgate.http.LiftgateException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * @author Dean
 * @date 9/17/2026
 */
class DomainNamesTest {
    @Test
    fun `platform hostnames are service-project-org under the deploy domain`() {
        assertEquals("api-shop-acme.liftgate.app", DomainNames.platform("Acme", "Shop", "API", "liftgate.app"))
    }

    @Test
    fun `platform hostnames longer than a dns label are rejected`() {
        assertFailsWith<LiftgateException> { DomainNames.platform("o".repeat(30), "p".repeat(30), "api", "liftgate.app") }
    }

    @Test
    fun `valid custom hostnames pass`() {
        listOf("example.com", "www.example.co.uk", "a-b.example.io", "1.example.org").forEach { DomainNames.validate(it, "liftgate.app") }
    }

    @Test
    fun `invalid and platform hostnames are rejected`() {
        listOf(
            "example",
            "-bad.example.com",
            "bad-.example.com",
            "under_score.example.com",
            "Upper.example.com",
            "a".repeat(64) + ".example.com",
            ("a".repeat(63) + ".").repeat(4) + "com",
            "app.liftgate.app",
            "liftgate.app",
        ).forEach { assertFailsWith<LiftgateException>(it) { DomainNames.validate(it, "liftgate.app") } }
    }
}
