package dev.liftgate.domain

import dev.liftgate.auth.randomToken
import dev.liftgate.db.Db
import dev.liftgate.db.Domains as DomainsTable
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.http.conflict
import dev.liftgate.http.invalid
import dev.liftgate.http.notFound
import dev.liftgate.org.Organization
import dev.liftgate.project.Project
import dev.liftgate.service.Service
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
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
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
)

/**
 * @author Dean
 * @date 9/17/2026
 */
class Domains(private val db: Db, private val deployDomain: String) {
    suspend fun ensurePlatform(service: Service, project: Project, org: Organization): Domain = db.tx {
        val hostname = DomainNames.platform(org.slug, project.slug, service.slug, deployDomain)
        DomainsTable.insertIgnore {
            it[id] = UUID.randomUUID()
            it[serviceId] = service.id
            it[DomainsTable.hostname] = hostname
            it[kind] = DomainKind.PLATFORM.sql
            it[verifiedAt] = now()
            it[certificateStatus] = "ready"
        }
        val domain = DomainsTable.selectAll().where { DomainsTable.hostname eq hostname }.single().toDomain()
        if (domain.serviceId != service.id) conflict("$hostname belongs to another service")
        domain
    }

    suspend fun addCustom(serviceId: UUID, hostname: String): Domain {
        val host = hostname.trim().lowercase().removeSuffix(".")
        DomainNames.validate(host, deployDomain)
        return db.tx {
            if (!DomainsTable.selectAll().where { (DomainsTable.hostname eq host) and DomainsTable.verifiedAt.isNotNull() }.empty()) conflict("$host is already verified by a service")
            DomainsTable.insertReturning {
                it[id] = UUID.randomUUID()
                it[DomainsTable.serviceId] = serviceId
                it[DomainsTable.hostname] = host
                it[kind] = DomainKind.CUSTOM.sql
                it[verificationToken] = randomToken()
            }.single().toDomain()
        }
    }

    suspend fun verify(id: UUID): Domain {
        val domain = byId(id) ?: notFound("domain")
        if (domain.verifiedAt != null) return domain
        val records = withContext(Dispatchers.IO) { txtRecords("_liftgate.${domain.hostname}") }
        if (domain.verificationToken !in records) invalid("no TXT record at _liftgate.${domain.hostname} holds the verification token")
        return db.tx {
            routingChanged(domain)
            DomainsTable.deleteWhere { (DomainsTable.hostname eq domain.hostname) and (DomainsTable.id neq id) and DomainsTable.verifiedAt.isNull() }
            DomainsTable.updateReturning(DomainsTable.columns, { DomainsTable.id eq id }) { it[verifiedAt] = now() }.single().toDomain()
        }
    }

    suspend fun byId(id: UUID): Domain? = db.tx { find(id) }

    suspend fun forService(serviceId: UUID): List<Domain> = db.tx {
        DomainsTable.selectAll().where { DomainsTable.serviceId eq serviceId }.orderBy(DomainsTable.hostname).map { it.toDomain() }
    }

    suspend fun verifiedCustom(): List<Domain> = db.tx {
        DomainsTable.selectAll().where { (DomainsTable.kind eq DomainKind.CUSTOM.sql) and DomainsTable.verifiedAt.isNotNull() }.map { it.toDomain() }
    }

    suspend fun delete(id: UUID) {
        db.tx {
            val domain = find(id) ?: return@tx
            if (domain.kind == DomainKind.PLATFORM) conflict("platform hostnames cannot be removed")
            DomainsTable.deleteWhere { DomainsTable.id eq id }
            if (domain.verifiedAt != null) routingChanged(domain)
        }
    }

    private fun find(id: UUID) = DomainsTable.selectAll().where { DomainsTable.id eq id }.singleOrNull()?.toDomain()

    private fun JdbcTransaction.routingChanged(domain: Domain) = enqueue(
        Subject.DOMAIN_VERIFY_REQUESTED,
        buildJsonObject { put("domainId", domain.id.toString()); put("serviceId", domain.serviceId.toString()) },
    )
}
