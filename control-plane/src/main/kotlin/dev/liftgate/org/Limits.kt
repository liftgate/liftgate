package dev.liftgate.org

import dev.liftgate.auth.OrgRole
import dev.liftgate.database.DatabaseSpec
import dev.liftgate.db.Databases
import dev.liftgate.db.Domains
import dev.liftgate.db.Environments
import dev.liftgate.db.Memberships
import dev.liftgate.db.Organizations
import dev.liftgate.db.Projects
import dev.liftgate.db.Services
import dev.liftgate.db.Users
import dev.liftgate.db.sql
import dev.liftgate.domain.DomainKind
import dev.liftgate.http.planLimit
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.toService
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.UUID

private const val CUSTOM_DOMAINS_LOCK = 7_261_696_401L
private val resources = listOf(Triple("replicas", "replicas", ""), Triple("cpuMillis", "CPU", "m"), Triple("memoryMb", "memory", " MB"), Triple("storageGb", "storage", " GB"))

/**
 * @author Dean
 * @date 9/27/2026
 */
class Limits(private val plans: Plans = Plans(), private val customDomainsMax: Int? = null) {
    fun ownedOrgs(ownerId: UUID) {
        Users.select(Users.id).where { Users.id eq ownerId }.forUpdate().toList()
        val owned = Memberships.selectAll().where { (Memberships.userId eq ownerId) and (Memberships.role eq OrgRole.OWNER.sql) }.count().toInt()
        count(plans.default, plans.of(plans.default).ownedOrgs, "organizations per owner", owned)
    }

    fun project(orgId: UUID) {
        val (name, plan) = lock(orgId)
        count(name, plan.projects, "projects", projects(orgId))
    }

    fun environment(projectId: UUID) {
        val (name, plan) = lock(Projects.select(Projects.orgId).where { Projects.id eq projectId }.single()[Projects.orgId])
        count(name, plan.environmentsPerProject, "environments per project", Environments.selectAll().where { (Environments.projectId eq projectId) and Environments.pullRequest.isNull() }.count().toInt())
    }

    fun preview(projectId: UUID) {
        val orgId = Projects.select(Projects.orgId).where { Projects.id eq projectId }.single()[Projects.orgId]
        val (name, plan) = lock(orgId)
        val previews = (Environments innerJoin Projects).selectAll().where { (Projects.orgId eq orgId) and Environments.pullRequest.isNotNull() }.count().toInt()
        count(name, plan.previewEnvironments, "preview environments", previews)
    }

    fun service(environmentId: UUID, spec: ServiceSpec) {
        val orgId = orgOfEnvironment(environmentId)
        val (name, plan) = lock(orgId)
        val specs = specs(orgId)
        count(name, plan.services, "services", specs.size)
        reserve(name, plan, footprints(orgId, specs), UUID.randomUUID(), spec.footprint)
    }

    fun resize(serviceId: UUID, spec: ServiceSpec) {
        val orgId = orgOfService(serviceId)
        val (name, plan) = lock(orgId)
        reserve(name, plan, footprints(orgId), serviceId, spec.footprint)
    }

    fun database(environmentId: UUID, spec: DatabaseSpec) {
        val orgId = orgOfEnvironment(environmentId)
        val (name, plan) = lock(orgId)
        reserve(name, plan, footprints(orgId), UUID.randomUUID(), listOf(1, spec.cpuMillis, spec.memoryMb, spec.storageGb))
    }

    fun customDomain(serviceId: UUID) {
        val orgId = orgOfService(serviceId)
        val (name, plan) = lock(orgId)
        count(name, plan.customDomains, "custom domains", customDomains(orgId))
        customDomainsMax?.let { max ->
            TransactionManager.current().exec("select pg_advisory_xact_lock(?)", listOf(LongColumnType() to CUSTOM_DOMAINS_LOCK))
            if (Domains.selectAll().where { Domains.kind eq DomainKind.CUSTOM.sql }.count() >= max) planLimit("this installation allows $max custom domains in total and all of them are in use")
        }
    }

    fun usage(orgId: UUID): Usage {
        val name = plans.name(Organizations.select(Organizations.plan).where { Organizations.id eq orgId }.single()[Organizations.plan])
        val specs = specs(orgId)
        val (replicas, cpuMillis, memoryMb, storageGb) = footprints(orgId, specs).values.total()
        return Usage(name, plans.of(name), projects(orgId), specs.size, customDomains(orgId), replicas, cpuMillis, memoryMb, storageGb)
    }

    private fun lock(orgId: UUID): Pair<String, Plan> {
        val name = plans.name(Organizations.select(Organizations.plan).where { Organizations.id eq orgId }.forUpdate().single()[Organizations.plan])
        return name to plans.of(name)
    }

    private fun orgOfEnvironment(environmentId: UUID) = (Environments innerJoin Projects).select(Projects.orgId).where { Environments.id eq environmentId }.single()[Projects.orgId]

    private fun orgOfService(serviceId: UUID) =
        (Services innerJoin Environments innerJoin Projects).select(Projects.orgId).where { Services.id eq serviceId }.single()[Projects.orgId]

    private fun projects(orgId: UUID) = Projects.selectAll().where { Projects.orgId eq orgId }.count().toInt()

    private fun customDomains(orgId: UUID) = (Domains innerJoin Services innerJoin Environments innerJoin Projects).selectAll()
        .where { (Projects.orgId eq orgId) and (Domains.kind eq DomainKind.CUSTOM.sql) }
        .count().toInt()

    private fun specs(orgId: UUID) = (Services innerJoin Environments innerJoin Projects).selectAll()
        .where { Projects.orgId eq orgId }
        .associate { it[Services.id] to it.toService().spec() }

    private fun footprints(orgId: UUID, specs: Map<UUID, ServiceSpec> = specs(orgId)) = specs.mapValues { it.value.footprint } +
        (Databases innerJoin Environments innerJoin Projects).select(Databases.id, Databases.cpuMillis, Databases.memoryMb, Databases.storageGb)
            .where { Projects.orgId eq orgId }
            .associate { it[Databases.id] to listOf(1, it[Databases.cpuMillis], it[Databases.memoryMb], it[Databases.storageGb]) }

    private val ServiceSpec.footprint get() = (if (kind == ServiceKind.CRON) 1 else replicas).let { listOf(it, it * cpuMillis, it * memoryMb, volume?.sizeGb ?: 0) }

    private fun Collection<List<Int>>.total() = resources.indices.map { i -> sumOf { it[i] } }

    private fun reserve(name: String, plan: Plan, current: Map<UUID, List<Int>>, id: UUID, footprint: List<Int>) {
        val was = current.values.total()
        val will = (current + (id to footprint)).values.total()
        listOf(plan.replicas, plan.cpuMillis, plan.memoryMb, plan.storageGb).forEachIndexed { i, limit ->
            val (field, label, unit) = resources[i]
            if (limit != null && will[i] > limit && will[i] > was[i]) planLimit("the $name plan's $label limit is $limit$unit across the organization, and this change needs ${will[i]}$unit", field)
        }
    }

    private fun count(name: String, limit: Int?, what: String, used: Int) {
        if (limit != null && used >= limit) planLimit("the $name plan's $what limit is $limit")
    }
}
