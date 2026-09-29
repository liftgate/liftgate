package dev.liftgate.build

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.liftgate.auth.ApiTokens
import dev.liftgate.auth.randomToken
import dev.liftgate.config.Config
import dev.liftgate.db.Builds as BuildsTable
import dev.liftgate.db.Db
import dev.liftgate.db.sql
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.Services
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.UUID

const val REGISTRY_TOKEN_SECONDS = 300L
const val JANITOR_ACCOUNT = "janitor"
private const val PULL_ACCOUNT = "pull"
private const val ISSUER = "liftgate"

/**
 * @author Dean
 * @date 9/27/2026
 */
class RegistryTokens(private val db: Db, private val services: Services, private val config: Config) {
    private val signing = config.registryTokens?.let {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(it.certificatePem.byteInputStream()) as X509Certificate
        Algorithm.RSA256(certificate.publicKey as RSAPublicKey, rsaPrivateKey(it.privateKeyPem, "LIFTGATE_REGISTRY_TOKEN_KEY")) to
            Base64.getEncoder().encodeToString(certificate.encoded)
    }

    suspend fun issue(buildId: UUID): String = randomToken().also { store(buildId, ApiTokens.hash(it)) }

    suspend fun revoke(buildId: UUID) = store(buildId, null)

    suspend fun token(account: String, password: String, scopes: List<String>): String? {
        val grants = grant(account, password) ?: return null
        val access = scopes.filter { it.startsWith("repository:") }.mapNotNull { scope ->
            val name = scope.substringAfter(':').substringBeforeLast(':')
            val actions = grants[name] ?: grants[null].orEmpty()
            scope.substringAfterLast(':').split(',').filter { it in actions }
                .takeIf { it.isNotEmpty() }?.let { mapOf("type" to "repository", "name" to name, "actions" to it) }
        }
        return sign(account, access)
    }

    private suspend fun grant(account: String, password: String): Map<out String?, Set<String>>? = when (account) {
        PULL_ACCOUNT -> mapOf(null to setOf("pull")).takeIf { matches(config.registryTokens?.pullPassword, password) }
        JANITOR_ACCOUNT -> mapOf(null to setOf("pull", "delete")).takeIf { matches(config.registryJanitorPassword, password) }
        else -> buildScope(account, password)?.let { scope ->
            val previous = with(scope) { "${org.slug}/${project.slug}-${service.slug}" }
            listOf(BuildJobs.repository(scope), previous).associateWith { setOf("pull", "push") } +
                listOfNotNull(services.production(scope)).associate { BuildJobs.repository(it) to setOf("pull") }
        }
    }

    private fun matches(expected: String?, password: String) = expected != null && MessageDigest.isEqual(expected.toByteArray(), password.toByteArray())

    private suspend fun buildScope(account: String, password: String): ServiceScope? {
        val buildId = runCatching { UUID.fromString(account.substringAfter('-')) }.getOrNull()?.takeIf { BuildJobs.name(it) == account } ?: return null
        val serviceId = db.tx {
            BuildsTable.select(BuildsTable.serviceId)
                .where { (BuildsTable.id eq buildId) and (BuildsTable.status eq BuildStatus.RUNNING.sql) and (BuildsTable.registrySecretHash eq ApiTokens.hash(password)) }
                .singleOrNull()?.get(BuildsTable.serviceId)
        } ?: return null
        return services.scope(serviceId)
    }

    private fun sign(subject: String, access: List<Map<String, Any>>): String {
        val (algorithm, certificate) = signing ?: error("LIFTGATE_REGISTRY_AUTH is not token")
        val now = Instant.now()
        return JWT.create()
            .withHeader(mapOf("x5c" to listOf(certificate)))
            .withIssuer(ISSUER)
            .withSubject(subject)
            .withAudience(config.registry)
            .withIssuedAt(now)
            .withNotBefore(now)
            .withExpiresAt(now.plusSeconds(REGISTRY_TOKEN_SECONDS))
            .withJWTId(randomToken())
            .withClaim("access", access)
            .sign(algorithm)
    }

    private suspend fun store(buildId: UUID, hash: String?) {
        db.tx { BuildsTable.update({ BuildsTable.id eq buildId }) { it[registrySecretHash] = hash } }
    }
}
