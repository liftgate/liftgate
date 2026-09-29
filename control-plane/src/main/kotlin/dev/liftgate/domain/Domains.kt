package dev.liftgate.domain

import dev.liftgate.auth.randomToken
import dev.liftgate.db.Db
import dev.liftgate.db.Domains as DomainsTable
import dev.liftgate.db.Services as ServicesTable
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.http.conflict
import dev.liftgate.http.invalid
import dev.liftgate.http.notFound
import dev.liftgate.org.Limits
import dev.liftgate.service.ServiceScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.util.UUID

fun ResultRow.toDomain() = Domain(
    this[DomainsTable.id],
    this[DomainsTable.serviceId],
    this[DomainsTable.hostname],
    this[DomainsTable.kind].toEnum(),
    this[DomainsTable.verificationToken],
    this[DomainsTable.verifiedAt]?.toInstant(),
    this[DomainsTable.certificateStatus],
    this[DomainsTable.certificateMessage],
    this[DomainsTable.edgeId],
)

private fun String.host() = trim().lowercase().removeSuffix(".")

/**
 * @author Dean
 * @date 9/17/2026
 */
class Domains(
    private val db: Db,
    private val deployDomain: String,
    private val limits: Limits = Limits(),
    private val edge: Cloudflare? = null,
    private val txt: (String) -> List<String> = ::txtRecords,
) {
    suspend fun ensurePlatform(scope: ServiceScope): Domain = db.tx {
        val service = scope.service
        ServicesTable.select(ServicesTable.id).where { ServicesTable.id eq service.id }.forUpdate().toList()
        DomainsTable.selectAll().where { (DomainsTable.serviceId eq service.id) and (DomainsTable.kind eq DomainKind.PLATFORM.sql) }.firstOrNull()?.toDomain()
            ?: DomainNames.platform(scope, deployDomain).firstNotNullOf { hostname ->
                DomainsTable.insertReturning(ignoreErrors = true) {
                    it[id] = UUID.randomUUID()
                    it[serviceId] = service.id
                    it[DomainsTable.hostname] = hostname
                    it[kind] = DomainKind.PLATFORM.sql
                    it[verifiedAt] = now()
                    it[certificateStatus] = "ready"
                }.singleOrNull()?.toDomain()
            }
    }

    suspend fun addCustom(serviceId: UUID, hostname: String): Domain {
        val host = hostname.host()
        DomainNames.validate(host, deployDomain)
        return db.tx {
            limits.customDomain(serviceId)
            if (verified(host)) conflict("$host is already verified by a service")
            DomainsTable.insertReturning {
                it[id] = UUID.randomUUID()
                it[DomainsTable.serviceId] = serviceId
                it[DomainsTable.hostname] = host
                it[kind] = DomainKind.CUSTOM.sql
                it[verificationToken] = randomToken()
            }.single().toDomain().withRecords(platform(serviceId))
        }
    }

    suspend fun verify(id: UUID): Domain {
        val domain = byId(id) ?: notFound("domain")
        if (domain.verifiedAt != null) return db.tx { domain.withRecords(platform(domain.serviceId)) }
        val records = withContext(Dispatchers.IO) { txt("_liftgate.${domain.hostname}") }
        if (domain.verificationToken !in records) invalid("no TXT record at _liftgate.${domain.hostname} holds the verification token")
        if (db.tx { verified(domain.hostname) }) conflict("${domain.hostname} is already verified by a service")
        val edgeId = edge?.create(domain.hostname)
        return db.tx {
            DomainsTable.deleteWhere { (DomainsTable.hostname eq domain.hostname) and (DomainsTable.id neq id) and DomainsTable.verifiedAt.isNull() }
            DomainsTable.updateReturning(DomainsTable.columns, { DomainsTable.id eq id }) {
                it[verifiedAt] = now()
                it[DomainsTable.edgeId] = edgeId
            }.singleOrNull()?.toDomain()?.also { routingChanged(it) }?.withRecords(platform(domain.serviceId))
        } ?: run {
            if (edgeId != null && !db.tx { verified(domain.hostname) }) edge?.delete(edgeId)
            notFound("domain")
        }
    }

    suspend fun allowed(hostname: String): Boolean = hostname.host().let { it == deployDomain || db.tx { verified(it) } }

    suspend fun certificate(hostname: String, state: CertificateState) {
        db.tx { setCertificate(hostname, state) }
    }

    suspend fun refreshEdge() {
        val edge = edge ?: return
        val domains = db.tx { DomainsTable.selectAll().where { DomainsTable.edgeId.isNotNull() }.map { it.toDomain() } }
        val hostnames = edge.hostnames().associateBy { it.id }
        val changed = domains.map { it to (hostnames[it.edgeId]?.certificate ?: CertificateState("failed", "Cloudflare has no custom hostname for ${it.hostname}")) }
            .filter { (domain, state) -> state != CertificateState(domain.certificateStatus, domain.certificateMessage) }
        if (changed.isNotEmpty()) db.tx { changed.forEach { (domain, state) -> setCertificate(domain.hostname, state) } }
    }

    suspend fun byId(id: UUID): Domain? = db.tx { find(id) }

    suspend fun forService(serviceId: UUID): List<Domain> = db.tx {
        val domains = DomainsTable.selectAll().where { DomainsTable.serviceId eq serviceId }.orderBy(DomainsTable.hostname).map { it.toDomain() }
        val platform = domains.firstOrNull { it.kind == DomainKind.PLATFORM }?.hostname
        domains.map { it.withRecords(platform) }
    }

    suspend fun verifiedCustom(): List<Domain> = db.tx {
        DomainsTable.selectAll().where { (DomainsTable.kind eq DomainKind.CUSTOM.sql) and DomainsTable.verifiedAt.isNotNull() }.map { it.toDomain() }
    }

    suspend fun delete(id: UUID) {
        val domain = byId(id) ?: return
        if (domain.kind == DomainKind.PLATFORM) conflict("platform hostnames cannot be removed")
        domain.edgeId?.let { edge?.delete(it) }
        db.tx {
            DomainsTable.deleteWhere { DomainsTable.id eq id }
            if (domain.verifiedAt != null) routingChanged(domain)
        }
    }

    private fun find(id: UUID) = DomainsTable.selectAll().where { DomainsTable.id eq id }.singleOrNull()?.toDomain()

    private fun verified(hostname: String) = !DomainsTable.selectAll().where { (DomainsTable.hostname eq hostname) and DomainsTable.verifiedAt.isNotNull() }.empty()

    private fun platform(serviceId: UUID) = DomainsTable.select(DomainsTable.hostname)
        .where { (DomainsTable.serviceId eq serviceId) and (DomainsTable.kind eq DomainKind.PLATFORM.sql) }
        .firstOrNull()?.get(DomainsTable.hostname)

    private fun Domain.withRecords(platform: String?) = if (kind == DomainKind.PLATFORM) this else copy(
        dnsRecords = listOfNotNull(
            verificationToken?.takeIf { verifiedAt == null }?.let { DnsRecord("TXT", "_liftgate.$hostname", it) },
            (edge?.cnameTarget ?: platform)?.let { DnsRecord("CNAME", hostname, it) },
        ),
    )

    private fun setCertificate(hostname: String, state: CertificateState) = DomainsTable.update({
        (DomainsTable.hostname eq hostname) and (DomainsTable.kind eq DomainKind.CUSTOM.sql) and DomainsTable.verifiedAt.isNotNull()
    }) {
        it[certificateStatus] = state.status
        it[certificateMessage] = state.message
    }

    private fun JdbcTransaction.routingChanged(domain: Domain) = enqueue(
        Subject.DOMAIN_VERIFY_REQUESTED,
        buildJsonObject { put("domainId", domain.id.toString()); put("serviceId", domain.serviceId.toString()) },
    )
}
