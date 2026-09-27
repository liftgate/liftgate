package dev.liftgate.org

import dev.liftgate.auth.OrgRole
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
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.UUID

private const val CUSTOM_DOMAINS_LOCK = 7_261_696_401L

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
        count(name, plan.environmentsPerProject, "environments per project", Environments.selectAll().where { Environments.projectId eq projectId }.count().toInt())
    }

    fun service(environmentId: UUID, spec: ServiceSpec) {
        val orgId = (Environments innerJoin Projects).select(Projects.orgId).where { Environments.id eq environmentId }.single()[Projects.orgId]
        val (name, plan) = lock(orgId)
        val specs = specs(orgId)
        count(name, plan.services, "services", specs.size)
        reserve(name, plan, specs.values, specs.values + spec)
    }

    fun resize(serviceId: UUID, spec: ServiceSpec) {
        val orgId = orgOfService(serviceId)
        val (name, plan) = lock(orgId)
        val specs = specs(orgId)
        reserve(name, plan, specs.values, (specs - serviceId).values + spec)
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
        val specs = specs(orgId).values
        val (replicas, cpuMillis, memoryMb) = reserved(specs)
        return Usage(name, plans.of(name), projects(orgId), specs.size, customDomains(orgId), replicas, cpuMillis, memoryMb)
    }

    private fun lock(orgId: UUID): Pair<String, Plan> {
        val name = plans.name(Organizations.select(Organizations.plan).where { Organizations.id eq orgId }.forUpdate().single()[Organizations.plan])
        return name to plans.of(name)
    }

    private fun orgOfService(serviceId: UUID) =
        (Services innerJoin Environments innerJoin Projects).select(Projects.orgId).where { Services.id eq serviceId }.single()[Projects.orgId]

    private fun projects(orgId: UUID) = Projects.selectAll().where { Projects.orgId eq orgId }.count().toInt()

    private fun customDomains(orgId: UUID) = (Domains innerJoin Services innerJoin Environments innerJoin Projects).selectAll()
        .where { (Projects.orgId eq orgId) and (Domains.kind eq DomainKind.CUSTOM.sql) }
        .count().toInt()

    private fun specs(orgId: UUID) = (Services innerJoin Environments innerJoin Projects).selectAll()
        .where { Projects.orgId eq orgId }
        .associate { it[Services.id] to it.toService().spec() }

    private val ServiceSpec.pods get() = if (kind == ServiceKind.CRON) 1 else replicas

    private fun reserved(specs: Collection<ServiceSpec>) =
        Triple(specs.sumOf { it.pods }, specs.sumOf { it.pods * it.cpuMillis }, specs.sumOf { it.pods * it.memoryMb })

    private fun reserve(name: String, plan: Plan, before: Collection<ServiceSpec>, after: Collection<ServiceSpec>) {
        val was = reserved(before).toList()
        val will = reserved(after).toList()
        listOf("replicas" to plan.replicas, "cpuMillis" to plan.cpuMillis, "memoryMb" to plan.memoryMb).forEachIndexed { i, (what, limit) ->
            if (limit != null && will[i] > limit && will[i] > was[i]) planLimit("the $name plan's $what limit is $limit across the organization, and this change needs ${will[i]}")
        }
    }

    private fun count(name: String, limit: Int?, what: String, used: Int) {
        if (limit != null && used >= limit) planLimit("the $name plan's $what limit is $limit")
    }
}
