package dev.liftgate.domain

import dev.liftgate.http.LiftgateException
import dev.liftgate.k8s.testEnvironment
import dev.liftgate.k8s.testOrg
import dev.liftgate.k8s.testProject
import dev.liftgate.k8s.testService
import dev.liftgate.service.ServiceScope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class DomainNamesTest {
    private val production = ServiceScope(testService, testEnvironment, testProject, testOrg)
    private val suffixed = Regex("""api-shop-[0-9a-z]{6}\.liftgate\.app""")

    @Test
    fun `production tries service-project-org and then the suffix form`() {
        val (readable, fallback) = DomainNames.platform(production, "liftgate.app")
        assertEquals("api-shop-acme.liftgate.app", readable)
        assertTrue(suffixed.matches(fallback), fallback)
    }

    @Test
    fun `other environments put the environment slug after the service`() {
        val staging = production.copy(environment = testEnvironment.copy(slug = "staging"))
        assertEquals("api-staging-shop-acme.liftgate.app", DomainNames.platform(staging, "liftgate.app").first())
    }

    @Test
    fun `the suffix is stable per service id and differs between services`() {
        val other = production.copy(service = testService.copy(id = UUID.randomUUID()))
        assertEquals(DomainNames.platform(production, "liftgate.app"), DomainNames.platform(production, "liftgate.app"))
        assertNotEquals(DomainNames.platform(production, "liftgate.app").last(), DomainNames.platform(other, "liftgate.app").last())
    }

    @Test
    fun `names longer than a dns label only get the suffix form, cut to fit one label`() {
        val long = production.copy(service = testService.copy(slug = "s".repeat(40)), project = testProject.copy(slug = "p".repeat(40)))
        val label = DomainNames.platform(long, "liftgate.app").single().substringBefore('.')
        assertTrue(Regex("s{40}-p{15}-[0-9a-z]{6}").matches(label), label)
        DomainNames.validate("$label.example.com", "liftgate.app")
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
