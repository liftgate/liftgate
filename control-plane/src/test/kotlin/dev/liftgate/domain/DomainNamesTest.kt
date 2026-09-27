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
    private val long = production.copy(service = testService.copy(slug = "s".repeat(40)), project = testProject.copy(slug = "p".repeat(40)))

    @Test
    fun `production tries service-project-org, then the suffix form, then the hyphen-free service id`() {
        val (readable, fallback, unique) = DomainNames.platform(production, "liftgate.app")
        assertEquals("api-shop-acme.liftgate.app", readable)
        assertTrue(suffixed.matches(fallback), fallback)
        assertEquals("${testService.id.toString().replace("-", "")}.liftgate.app", unique)
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
        assertNotEquals(DomainNames.platform(production, "liftgate.app")[1], DomainNames.platform(other, "liftgate.app")[1])
    }

    @Test
    fun `names longer than a dns label skip the readable form and cut the suffix form to one label`() {
        val names = DomainNames.platform(long, "liftgate.app")
        assertEquals(2, names.size)
        val label = names.first().substringBefore('.')
        assertTrue(Regex("s{40}-p{15}-[0-9a-z]{6}").matches(label), label)
        DomainNames.validate("$label.example.com", "liftgate.app")
    }

    @Test
    fun `every name fits a dns hostname under the longest deploy domain config accepts`() {
        val deployDomain = listOf("a".repeat(63), "b".repeat(63), "c".repeat(DomainNames.MAX_DEPLOY_DOMAIN - 128)).joinToString(".")
        val names = DomainNames.platform(long, deployDomain) + DomainNames.platform(production, deployDomain)
        assertEquals(253, names.maxOf { it.length })
        names.forEach { DomainNames.validate(it, "liftgate.app") }
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
