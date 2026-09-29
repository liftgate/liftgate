package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.domain.CertificateState
import dev.liftgate.domain.DomainKind
import dev.liftgate.domain.Domains
import dev.liftgate.testConfig
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Dean
 * @date 9/27/2026
 */
@EnableKubernetesMockClient(crud = true)
class CertificateWatcherTest {
    lateinit var client: KubernetesClient

    private val scope = CoroutineScope(Dispatchers.Default)
    private val domain = testDomain("shop.example.com", DomainKind.CUSTOM)

    private fun certificate(vararg status: Pair<String, Any>): GenericKubernetesResource = GenericKubernetesResourceBuilder(Resources.certificate(domain, "liftgate-system", "letsencrypt"))
        .addToAdditionalProperties("status", mapOf(*status))
        .build()

    private fun condition(type: String, status: String, message: String) = mapOf("type" to type, "status" to status, "reason" to type, "message" to message)

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun `the ready condition decides between ready, pending and failed`() {
        assertEquals(CertificateState("ready"), CertificateWatcher.state(certificate("conditions" to listOf(condition("Ready", "True", "Certificate is up to date")))))
        assertEquals(CertificateState("pending"), CertificateWatcher.state(certificate()))
        assertEquals(
            CertificateState("pending", "Issuing certificate as Secret does not exist"),
            CertificateWatcher.state(certificate("conditions" to listOf(condition("Ready", "False", "Issuing certificate as Secret does not exist")))),
        )
        assertEquals(
            CertificateState("failed", "The certificate request has failed to complete and will be retried"),
            CertificateWatcher.state(
                certificate(
                    "conditions" to listOf(condition("Ready", "False", "Issuing certificate as Secret does not exist"), condition("Issuing", "False", "The certificate request has failed to complete and will be retried")),
                    "lastFailureTime" to "2026-09-27T00:00:00Z",
                ),
            ),
        )
    }

    @Test
    fun `a certificate turning ready sets the domain ready`() {
        val domains = mockk<Domains>(relaxUnitFun = true)
        val app = mockk<App> {
            every { config } returns testConfig()
            every { this@mockk.scope } returns this@CertificateWatcherTest.scope
            every { this@mockk.domains } returns domains
        }
        val certificates = client.genericKubernetesResources(certificateContext).inNamespace("liftgate-system")
        certificates.resource(certificate()).create()
        CertificateWatcher(app, client).watch().use {
            coVerify(timeout = 10_000) { domains.certificate("shop.example.com", CertificateState("pending")) }
            certificates.withName("shop.example.com").edit {
                GenericKubernetesResourceBuilder(it).addToAdditionalProperties("status", mapOf("conditions" to listOf(condition("Ready", "True", "Certificate is up to date")))).build()
            }
            coVerify(timeout = 10_000) { domains.certificate("shop.example.com", CertificateState("ready")) }
        }
    }
}
