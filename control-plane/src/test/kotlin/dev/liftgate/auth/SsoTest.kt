package dev.liftgate.auth

import dev.liftgate.cache.Cache
import dev.liftgate.db.Db
import dev.liftgate.http.LiftgateException
import dev.liftgate.testConfig
import io.ktor.http.Url
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import net.shibboleth.shared.xml.SerializeSupport
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.AfterAll
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport
import org.opensaml.core.xml.schema.XSString
import org.opensaml.core.xml.util.XMLObjectSupport
import org.opensaml.saml.common.SignableSAMLObject
import org.opensaml.saml.common.xml.SAMLConstants
import org.opensaml.saml.saml2.core.Assertion
import org.opensaml.saml.saml2.core.Attribute
import org.opensaml.saml.saml2.core.AttributeStatement
import org.opensaml.saml.saml2.core.AttributeValue
import org.opensaml.saml.saml2.core.Audience
import org.opensaml.saml.saml2.core.AudienceRestriction
import org.opensaml.saml.saml2.core.AuthnRequest
import org.opensaml.saml.saml2.core.Conditions
import org.opensaml.saml.saml2.core.Issuer
import org.opensaml.saml.saml2.core.NameID
import org.opensaml.saml.saml2.core.NameIDType
import org.opensaml.saml.saml2.core.Response
import org.opensaml.saml.saml2.core.Status
import org.opensaml.saml.saml2.core.StatusCode
import org.opensaml.saml.saml2.core.Subject
import org.opensaml.saml.saml2.core.SubjectConfirmation
import org.opensaml.saml.saml2.core.SubjectConfirmationData
import org.opensaml.saml.saml2.metadata.EntityDescriptor
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.support.SignatureConstants
import org.opensaml.xmlsec.signature.support.Signer
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val ORG = "acme"
private const val IDP = "https://idp.acme.com/metadata"
private const val BROWSER = "browser-nonce"

/**
 * @author Dean
 * @date 9/18/2026
 */
class SsoTest {
    companion object {
        private val cache = Cache(testConfig())
        private val idp = credential()
        private val stranger = credential()

        @AfterAll
        @JvmStatic
        fun close() = cache.close()

        private fun credential(): BasicX509Credential {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=idp.acme.com")
            val now = Instant.now()
            val holder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date.from(now.minusSeconds(60)), Date.from(now.plus(Duration.ofDays(1))), name, keys.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
            return BasicX509Credential(JcaX509CertificateConverter().getCertificate(holder), keys.private)
        }
    }

    private val connection = SsoConnection(
        UUID.randomUUID(),
        UUID.randomUUID(),
        SsoSettings(IDP, "https://idp.acme.com/sso?tenant=1", Base64.getEncoder().encodeToString(idp.entityCertificate.encoded), listOf("acme.com")),
    )
    private val sso = Sso("https://api.liftgate.test", mockk<Db> { coEvery { tx<SsoConnection?>(any()) } returns connection }, cache, mockk())

    private fun login(next: String) = Url(runBlocking { sso.loginUrl(ORG, next, BROWSER) })

    private fun authnRequest(url: Url) = InflaterInputStream(Base64.getDecoder().decode(url.parameters["SAMLRequest"]!!).inputStream(), Inflater(true)).readBytes()

    private fun request(next: String = "/projects") =
        (XMLObjectSupport.unmarshallFromInputStream(XMLObjectProviderRegistrySupport.getParserPool()!!, authnRequest(login(next)).inputStream()) as AuthnRequest).id!!

    private fun issuer() = saml<Issuer>(Issuer.DEFAULT_ELEMENT_NAME).apply { value = IDP }

    private fun assertion(
        requestId: String,
        email: String = "dean@acme.com",
        id: String = "_${UUID.randomUUID()}",
        audience: String = sso.entityId(ORG),
        expiry: Instant = Instant.now().plusSeconds(300),
    ) = saml<Assertion>(Assertion.DEFAULT_ELEMENT_NAME).apply {
        this.id = id
        issueInstant = Instant.now()
        issuer = issuer()
        subject = saml<Subject>(Subject.DEFAULT_ELEMENT_NAME).apply {
            nameID = saml<NameID>(NameID.DEFAULT_ELEMENT_NAME).apply {
                value = email
                format = NameIDType.EMAIL
            }
            subjectConfirmations += saml<SubjectConfirmation>(SubjectConfirmation.DEFAULT_ELEMENT_NAME).apply {
                method = SubjectConfirmation.METHOD_BEARER
                subjectConfirmationData = saml<SubjectConfirmationData>(SubjectConfirmationData.DEFAULT_ELEMENT_NAME).apply {
                    recipient = sso.acsUrl(ORG)
                    inResponseTo = requestId
                    notOnOrAfter = expiry
                }
            }
        }
        conditions = saml<Conditions>(Conditions.DEFAULT_ELEMENT_NAME).apply {
            notBefore = expiry.minusSeconds(600)
            notOnOrAfter = expiry
            audienceRestrictions += saml<AudienceRestriction>(AudienceRestriction.DEFAULT_ELEMENT_NAME).apply {
                audiences += saml<Audience>(Audience.DEFAULT_ELEMENT_NAME).apply { uri = audience }
            }
        }
    }

    private fun signed(
        requestId: String,
        assertion: Assertion = assertion(requestId),
        signer: BasicX509Credential? = idp,
        signResponse: Boolean = false,
        injected: Assertion? = null,
    ): String {
        val response = saml<Response>(Response.DEFAULT_ELEMENT_NAME).apply {
            id = "_${UUID.randomUUID()}"
            issueInstant = Instant.now()
            inResponseTo = requestId
            destination = sso.acsUrl(ORG)
            issuer = issuer()
            status = saml<Status>(Status.DEFAULT_ELEMENT_NAME).apply {
                statusCode = saml<StatusCode>(StatusCode.DEFAULT_ELEMENT_NAME).apply { value = StatusCode.SUCCESS }
            }
            assertions += listOfNotNull(assertion, injected)
        }
        val signable: SignableSAMLObject = if (signResponse) response else assertion
        signer?.let {
            signable.signature = saml<Signature>(Signature.DEFAULT_ELEMENT_NAME).apply {
                signingCredential = it
                signatureAlgorithm = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256
                canonicalizationAlgorithm = SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS
            }
        }
        XMLObjectSupport.marshall(response)
        signable.signature?.let(Signer::signObject)
        return encode(response.dom!!)
    }

    private fun encode(node: Node) = Base64.getEncoder().encodeToString(SerializeSupport.nodeToString(node).toByteArray())

    private fun verify(encoded: String, browser: String = BROWSER) = sso.verify(ORG, connection, encoded, browser)

    private fun rejects(encoded: String, reason: String) {
        val error = assertFailsWith<LiftgateException> { verify(encoded) }
        assertEquals("invalid_saml", error.code)
        assertTrue(reason in error.message, error.message)
    }

    @Test
    fun `login redirects to the idp with a deflated AuthnRequest`() {
        val url = login("/projects")
        val xml = String(authnRequest(url))
        assertEquals("idp.acme.com", url.host)
        assertEquals("1", url.parameters["tenant"])
        assertTrue("""AssertionConsumerServiceURL="https://api.liftgate.test/api/v1/auth/sso/acme/acs"""" in xml, xml)
        assertTrue(">https://api.liftgate.test/api/v1/auth/sso/acme/metadata</saml2:Issuer>" in xml, xml)
    }

    @Test
    fun `a signed assertion signs the user in and returns to next`() {
        val login = verify(signed(request("/acme/web")))
        assertEquals(SamlLogin("dean@acme.com", "dean@acme.com", "/acme/web"), login)
    }

    @Test
    fun `a signed response covers an unsigned assertion`() {
        assertEquals("dean@acme.com", verify(signed(request(), signResponse = true)).email)
    }

    @Test
    fun `the email attribute wins over an opaque NameID`() {
        val requestId = request()
        val assertion = assertion(requestId, email = "00u1opaque").apply {
            attributeStatements += saml<AttributeStatement>(AttributeStatement.DEFAULT_ELEMENT_NAME).apply {
                attributes += saml<Attribute>(Attribute.DEFAULT_ELEMENT_NAME).apply {
                    name = "email"
                    attributeValues += (XMLObjectSupport.getBuilder(XSString.TYPE_NAME)!!.buildObject(AttributeValue.DEFAULT_ELEMENT_NAME, XSString.TYPE_NAME) as XSString).apply { value = "Dean@Acme.com" }
                }
            }
        }
        assertEquals(SamlLogin("00u1opaque", "dean@acme.com", "/projects"), verify(signed(requestId, assertion)))
    }

    @Test
    fun `an unsigned response is rejected`() = rejects(signed(request(), signer = null), "not signed")

    @Test
    fun `a signature from another certificate is rejected`() = rejects(signed(request(), signer = stranger), "signature")

    @Test
    fun `an assertion for another audience is rejected`() {
        val requestId = request()
        rejects(signed(requestId, assertion(requestId, audience = "https://evil.example/metadata")), "audience")
    }

    @Test
    fun `an expired assertion is rejected`() {
        val requestId = request()
        rejects(signed(requestId, assertion(requestId, expiry = Instant.now().minusSeconds(600))), "expired")
    }

    @Test
    fun `a response posted from another browser is rejected`() {
        val error = assertFailsWith<LiftgateException> { verify(signed(request()), browser = "attacker-nonce") }
        assertTrue("another browser" in error.message, error.message)
    }

    @Test
    fun `only a verified domain vouches for the email`() {
        val identities = mutableListOf<VerifiedIdentity>()
        val signIn = mockk<SignIn> { coEvery { complete(capture(identities)) } returns SignedIn(UUID.randomUUID(), "session") }
        listOf(emptyList(), listOf("acme.com")).forEach { verified ->
            val current = connection.copy(settings = connection.settings.copy(verifiedDomains = verified))
            val sso = Sso("https://api.liftgate.test", mockk<Db> { coEvery { tx<SsoConnection?>(any()) } returns current }, cache, signIn)
            runBlocking { sso.acs(ORG, signed(request()), BROWSER) }
        }
        assertEquals(listOf(false, true), identities.map { it.emailVerified })
    }

    @Test
    fun `a response to an unknown request is rejected`() = rejects(signed("_never-issued"), "unknown or expired")

    @Test
    fun `a request id is used once`() {
        val requestId = request()
        verify(signed(requestId))
        rejects(signed(requestId), "unknown or expired")
    }

    @Test
    fun `a replayed assertion is rejected`() {
        val id = "_${UUID.randomUUID()}"
        val first = request()
        verify(signed(first, assertion(first, id = id)))
        val second = request()
        rejects(signed(second, assertion(second, id = id)), "already used")
    }

    @Test
    fun `an email outside the allowed domains is rejected`() {
        val requestId = request()
        rejects(signed(requestId, assertion(requestId, email = "dean@other.com")), "email domains")
    }

    @Test
    fun `an unsigned assertion injected next to the signed one is rejected`() {
        val requestId = request()
        rejects(signed(requestId, injected = assertion(requestId, email = "ceo@acme.com")), "exactly one assertion")
    }

    @Test
    fun `a forged copy that hides the signed original is rejected`() {
        val requestId = request()
        val document = XMLObjectProviderRegistrySupport.getParserPool()!!.parse(Base64.getDecoder().decode(signed(requestId)).inputStream())
        val genuine = document.getElementsByTagNameNS(SAMLConstants.SAML20_NS, "Assertion").item(0) as Element
        val forged = genuine.cloneNode(true) as Element
        forged.getElementsByTagNameNS(SAMLConstants.SAML20_NS, "NameID").item(0).textContent = "ceo@acme.com"
        genuine.parentNode.replaceChild(forged, genuine)
        document.documentElement.insertBefore(document.createElementNS(SAMLConstants.SAML20P_NS, "saml2p:Extensions").apply { appendChild(genuine) }, forged)
        rejects(encode(document), "repeats an ID")
    }

    @Test
    fun `metadata names the entity id and the acs`() {
        val metadata = XMLObjectSupport.unmarshallFromInputStream(XMLObjectProviderRegistrySupport.getParserPool()!!, sso.metadata(ORG).byteInputStream()) as EntityDescriptor
        assertEquals(sso.entityId(ORG), metadata.entityID)
        assertEquals(sso.acsUrl(ORG), metadata.getSPSSODescriptor(SAMLConstants.SAML20P_NS)!!.assertionConsumerServices.single().location)
    }

    @Test
    fun `settings are normalized and validated`() {
        val settings = connection.settings.copy(emailDomains = listOf(" @Acme.COM", "acme.io"), defaultRole = OrgRole.ADMIN).validated()
        assertEquals(listOf("acme.com", "acme.io"), settings.emailDomains)
        assertTrue(settings.idpCertificate.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertEquals(idp.entityCertificate, certificate(settings.idpCertificate))
        listOf(
            connection.settings.copy(idpSsoUrl = "http://idp.acme.com/sso"),
            connection.settings.copy(idpCertificate = "not a certificate"),
            connection.settings.copy(emailDomains = emptyList()),
            connection.settings.copy(emailDomains = listOf("acme")),
            connection.settings.copy(defaultRole = OrgRole.OWNER),
            connection.settings.copy(idpEntityId = " "),
        ).forEach { assertEquals("invalid", assertFailsWith<LiftgateException> { it.validated() }.code) }
    }
}
