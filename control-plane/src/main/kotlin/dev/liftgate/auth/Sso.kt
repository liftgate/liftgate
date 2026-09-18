package dev.liftgate.auth

import dev.liftgate.cache.Cache
import dev.liftgate.db.Db
import dev.liftgate.db.Memberships
import dev.liftgate.db.Organizations
import dev.liftgate.db.SsoConnections
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.domain.txtRecords
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.invalid
import dev.liftgate.http.notFound
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.shibboleth.shared.xml.SerializeSupport
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.anyFrom
import org.jetbrains.exposed.v1.core.compoundOr
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import org.opensaml.core.config.InitializationService
import org.opensaml.core.xml.XMLObject
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport
import org.opensaml.core.xml.schema.XSAny
import org.opensaml.core.xml.schema.XSString
import org.opensaml.core.xml.util.XMLObjectSupport
import org.opensaml.saml.common.xml.SAMLConstants
import org.opensaml.saml.saml2.core.Assertion
import org.opensaml.saml.saml2.core.AuthnRequest
import org.opensaml.saml.saml2.core.Issuer
import org.opensaml.saml.saml2.core.Response
import org.opensaml.saml.saml2.core.StatusCode
import org.opensaml.saml.saml2.core.SubjectConfirmation
import org.opensaml.saml.security.impl.SAMLSignatureProfileValidator
import org.opensaml.security.credential.Credential
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.support.SignatureException
import org.opensaml.xmlsec.signature.support.SignatureValidator
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.xml.namespace.QName

const val SAML = "saml"
private const val REQUEST_MINUTES = 10L
private val skew = Duration.ofMinutes(3)
private val pemArmor = Regex("-----(BEGIN|END) CERTIFICATE-----")
private val domainPattern = Regex("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]")
private val emailAttributes = setOf(
    "email",
    "mail",
    "emailaddress",
    "urn:oid:0.9.2342.19200300.100.1.3",
    "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
)

/**
 * @author Dean
 * @date 9/18/2026
 */
@Serializable
data class SsoSettings(
    val idpEntityId: String,
    val idpSsoUrl: String,
    val idpCertificate: String,
    val emailDomains: List<String>,
    val defaultRole: OrgRole = OrgRole.MEMBER,
    val verifiedDomains: List<String> = emptyList(),
    val verificationToken: String? = null,
)

/**
 * @author Dean
 * @date 9/18/2026
 */
data class SsoConnection(val id: UUID, val orgId: UUID, val settings: SsoSettings)

/**
 * @author Dean
 * @date 9/18/2026
 */
data class SamlLogin(val nameId: String, val email: String, val next: String)

inline fun <reified T : XMLObject> saml(name: QName) = XMLObjectSupport.buildXMLObject(name) as T

fun certificate(text: String): X509Certificate = runCatching {
    CertificateFactory.getInstance("X.509").generateCertificate(Base64.getMimeDecoder().decode(text.replace(pemArmor, "")).inputStream()) as X509Certificate
}.getOrElse { invalid("idpCertificate must be a PEM encoded X.509 certificate") }

fun SsoSettings.validated(): SsoSettings {
    val url = runCatching { URI(idpSsoUrl.trim()) }.getOrNull()?.takeIf { it.scheme == "https" && !it.host.isNullOrEmpty() }
        ?: invalid("idpSsoUrl must be an https URL")
    val domains = emailDomains.map { it.trim().lowercase().removePrefix("@") }.distinct()
    if (domains.isEmpty() || !domains.all(domainPattern::matches)) invalid("emailDomains must list domains such as acme.com")
    if (defaultRole == OrgRole.OWNER) invalid("defaultRole must be member or admin")
    val pem = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate(idpCertificate).encoded)
    return SsoSettings(idpEntityId.trim().ifEmpty { invalid("idpEntityId is required") }, url.toString(), "-----BEGIN CERTIFICATE-----\n$pem\n-----END CERTIFICATE-----", domains, defaultRole)
}

/**
 * @author Dean
 * @date 9/18/2026
 */
class Sso(private val publicUrl: String, private val db: Db, private val cache: Cache, private val signIn: SignIn) {
    companion object {
        init {
            InitializationService.initialize()
        }
    }

    fun entityId(org: String) = "$publicUrl/api/v1/auth/sso/$org/metadata"

    fun acsUrl(org: String) = "$publicUrl/api/v1/auth/sso/$org/acs"

    fun metadata(org: String) = """
        <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="${entityId(org)}">
            <md:SPSSODescriptor AuthnRequestsSigned="false" WantAssertionsSigned="true" protocolSupportEnumeration="${SAMLConstants.SAML20P_NS}">
                <md:NameIDFormat>urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress</md:NameIDFormat>
                <md:AssertionConsumerService Binding="${SAMLConstants.SAML2_POST_BINDING_URI}" Location="${acsUrl(org)}" index="0" isDefault="true"/>
            </md:SPSSODescriptor>
        </md:EntityDescriptor>
    """.trimIndent()

    suspend fun settings(orgId: UUID) = find { SsoConnections.orgId eq orgId }?.settings

    suspend fun save(orgId: UUID, settings: SsoSettings): SsoSettings {
        val valid = settings.validated()
        return db.tx {
            ensureUnclaimed(orgId, valid.emailDomains)
            val verified = SsoConnections.select(SsoConnections.verifiedDomains).where { SsoConnections.orgId eq orgId }.singleOrNull()?.get(SsoConnections.verifiedDomains).orEmpty()
            SsoConnections.upsertReturning(SsoConnections.orgId, onUpdateExclude = listOf(SsoConnections.id, SsoConnections.createdAt, SsoConnections.verificationToken)) {
                it[id] = UUID.randomUUID()
                it[SsoConnections.orgId] = orgId
                it[idpEntityId] = valid.idpEntityId
                it[idpSsoUrl] = valid.idpSsoUrl
                it[idpCertificate] = valid.idpCertificate
                it[emailDomains] = valid.emailDomains
                it[verifiedDomains] = verified.filter(valid.emailDomains::contains)
                it[verificationToken] = randomToken()
                it[defaultRole] = valid.defaultRole.sql
            }.single().toConnection().settings
        }
    }

    suspend fun verifyDomains(orgId: UUID): SsoSettings {
        val settings = settings(orgId) ?: notFound("SSO connection")
        val pending = settings.emailDomains - settings.verifiedDomains.toSet()
        val proven = withContext(Dispatchers.IO) { pending.filter { settings.verificationToken in txtRecords("_liftgate.$it") } }
        if (pending.isNotEmpty() && proven.isEmpty()) invalid("no TXT record at ${pending.joinToString { "_liftgate.$it" }} holds the verification token")
        val verified = settings.verifiedDomains + proven
        db.tx {
            ensureUnclaimed(orgId, proven)
            SsoConnections.update({ SsoConnections.orgId eq orgId }) { it[verifiedDomains] = verified }
        }
        return settings.copy(verifiedDomains = verified)
    }

    suspend fun delete(orgId: UUID) {
        if (db.tx { SsoConnections.deleteWhere { SsoConnections.orgId eq orgId } } == 0) notFound("SSO connection")
    }

    suspend fun lookup(email: String): String? = normalizeEmail(email).substringAfter('@').let { domain ->
        db.tx {
            (SsoConnections innerJoin Organizations).select(Organizations.slug)
                .where { stringParam(domain) eq anyFrom(SsoConnections.verifiedDomains) }
                .singleOrNull()?.get(Organizations.slug)
        }
    }

    suspend fun loginUrl(org: String, next: String, browser: String): String {
        val connection = connection(org)
        val requestId = "_${randomToken()}"
        val request = saml<AuthnRequest>(AuthnRequest.DEFAULT_ELEMENT_NAME).apply {
            id = requestId
            issueInstant = Instant.now()
            destination = connection.settings.idpSsoUrl
            assertionConsumerServiceURL = acsUrl(org)
            protocolBinding = SAMLConstants.SAML2_POST_BINDING_URI
            issuer = saml<Issuer>(Issuer.DEFAULT_ELEMENT_NAME).apply { value = entityId(org) }
        }
        cache.samlRequests.set(requestId, "$org|$browser|$next", REQUEST_MINUTES, TimeUnit.MINUTES)
        return URLBuilder(connection.settings.idpSsoUrl).apply { parameters.append("SAMLRequest", deflate(SerializeSupport.nodeToString(XMLObjectSupport.marshall(request)))) }.buildString()
    }

    suspend fun acs(org: String, encoded: String, browser: String): Pair<SignedIn, String> {
        val connection = connection(org)
        val login = verify(org, connection, encoded, browser)
        val domainVerified = login.email.substringAfter('@') in connection.settings.verifiedDomains
        val signedIn = signIn.complete(VerifiedIdentity(SAML, "${connection.id}:${login.nameId}", login.email, emailVerified = domainVerified))
        db.tx {
            Memberships.insertIgnore {
                it[orgId] = connection.orgId
                it[userId] = signedIn.userId
                it[role] = connection.settings.defaultRole.sql
            }
        }
        return signedIn to login.next
    }

    fun verify(org: String, connection: SsoConnection, encoded: String, browser: String, now: Instant = Instant.now()): SamlLogin {
        val response = parse(encoded)
        val assertion = response.assertions.singleOrNull()?.takeIf { response.encryptedAssertions.isEmpty() } ?: rejected("the response must carry exactly one assertion")
        val credential = BasicX509Credential(certificate(connection.settings.idpCertificate))
        listOfNotNull(response.signature, assertion.signature).ifEmpty { rejected("the response is not signed") }.forEach { it.verify(credential) }
        val requestId = response.inResponseTo ?: rejected("the response does not answer a sign-in request")
        val (requestOrg, requestBrowser, next) = cache.samlRequests.remove(requestId)?.split('|', limit = 3) ?: rejected("the response answers an unknown or expired sign-in request")
        val conditions = assertion.conditions ?: rejected("the assertion has no conditions")
        val expiry = conditions.notOnOrAfter ?: rejected("the assertion has no expiry")
        val subject = assertion.subject ?: rejected("the assertion has no subject")
        val idp = connection.settings.idpEntityId
        when {
            requestOrg != org -> rejected("the sign-in request belongs to another organization")
            !MessageDigest.isEqual(requestBrowser.toByteArray(), browser.toByteArray()) -> rejected("the sign-in was started in another browser")
            response.status?.statusCode?.value != StatusCode.SUCCESS -> rejected("the identity provider did not sign you in")
            response.destination.let { it != null && it != acsUrl(org) } -> rejected("the response is addressed to another service")
            assertion.issuer?.value != idp || response.issuer?.value.let { it != null && it != idp } -> rejected("the assertion comes from another issuer")
            !within(conditions.notBefore, expiry, now) -> rejected("the assertion has expired or is not valid yet")
            conditions.audienceRestrictions.isEmpty() || conditions.audienceRestrictions.any { restriction -> restriction.audiences.none { it.uri == entityId(org) } } ->
                rejected("the assertion is meant for another audience")
            subject.subjectConfirmations.none { it.confirms(acsUrl(org), requestId, now) } -> rejected("the assertion is not addressed to this service")
        }
        val nameId = subject.nameID?.value?.trim().orEmpty().ifEmpty { rejected("the assertion has no NameID") }
        val email = normalizeEmail(assertion.emailAttribute() ?: nameId)
        if (email.substringAfter('@') !in connection.settings.emailDomains) rejected("$email is not in this organization's email domains")
        if (cache.samlAssertions.putIfAbsent(assertion.id ?: rejected("the assertion has no ID"), org, Duration.between(now, expiry.plus(skew)).seconds + 1, TimeUnit.SECONDS) != null) {
            rejected("the assertion was already used")
        }
        return SamlLogin(nameId, email, next)
    }

    private fun ensureUnclaimed(orgId: UUID, domains: List<String>) {
        if (domains.isEmpty()) return
        val claimed = domains.map { stringParam(it) eq anyFrom(SsoConnections.verifiedDomains) }.compoundOr()
        if (!SsoConnections.select(SsoConnections.id).where { (SsoConnections.orgId neq orgId) and claimed }.empty()) {
            throw LiftgateException(HttpStatusCode.Conflict, "domain_in_use", "another organization already verified one of these email domains")
        }
    }

    private suspend fun connection(org: String) = find { Organizations.slug eq org } ?: notFound("SSO connection")

    private suspend fun find(where: () -> Op<Boolean>) = db.tx { (SsoConnections innerJoin Organizations).selectAll().where(where).singleOrNull()?.toConnection() }
}

private fun ResultRow.toConnection() = SsoConnection(
    this[SsoConnections.id],
    this[SsoConnections.orgId],
    SsoSettings(
        this[SsoConnections.idpEntityId],
        this[SsoConnections.idpSsoUrl],
        this[SsoConnections.idpCertificate],
        this[SsoConnections.emailDomains],
        this[SsoConnections.defaultRole].toEnum(),
        this[SsoConnections.verifiedDomains],
        this[SsoConnections.verificationToken],
    ),
)

private fun parse(encoded: String): Response {
    val document = runCatching { XMLObjectProviderRegistrySupport.getParserPool()!!.parse(Base64.getMimeDecoder().decode(encoded).inputStream()) }
        .getOrElse { rejected("the SAML response is not valid XML") }
    val ids = document.getElementsByTagNameNS("*", "*").let { nodes -> (0 until nodes.length).mapNotNull { (nodes.item(it) as Element).getAttributeNode("ID")?.value } }
    if (ids.size != ids.toSet().size) rejected("the SAML response repeats an ID")
    return runCatching { XMLObjectSupport.getUnmarshaller(document.documentElement)!!.unmarshall(document.documentElement) as Response }
        .getOrElse { rejected("the SAML response is not a SAML 2.0 Response") }
}

private fun Signature.verify(credential: Credential) = try {
    SAMLSignatureProfileValidator().validate(this)
    SignatureValidator.validate(this, credential)
} catch (_: SignatureException) {
    rejected("the SAML signature does not match the identity provider certificate")
}

private fun SubjectConfirmation.confirms(acs: String, requestId: String, now: Instant) = method == SubjectConfirmation.METHOD_BEARER &&
    subjectConfirmationData?.let { it.recipient == acs && it.inResponseTo == requestId && within(it.notBefore, it.notOnOrAfter, now) } == true

private fun within(notBefore: Instant?, notOnOrAfter: Instant?, now: Instant) =
    (notBefore == null || !now.plus(skew).isBefore(notBefore)) && (notOnOrAfter == null || now.minus(skew).isBefore(notOnOrAfter))

private fun Assertion.emailAttribute() = attributeStatements.flatMap { it.attributes }.firstOrNull { it.name?.lowercase() in emailAttributes }
    ?.attributeValues?.firstNotNullOfOrNull { (it as? XSString)?.value ?: (it as? XSAny)?.textContent }

private fun deflate(xml: String): String = Base64.getEncoder().encodeToString(
    ByteArrayOutputStream().also { out -> DeflaterOutputStream(out, Deflater(Deflater.DEFLATED, true)).use { it.write(xml.toByteArray()) } }.toByteArray(),
)

private fun rejected(reason: String): Nothing = throw LiftgateException(HttpStatusCode.BadRequest, "invalid_saml", reason)
