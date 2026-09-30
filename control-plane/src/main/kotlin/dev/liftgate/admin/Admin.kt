package dev.liftgate.admin

import dev.liftgate.auth.GITHUB
import dev.liftgate.auth.Mailer
import dev.liftgate.auth.OrgRole
import dev.liftgate.auth.audit
import dev.liftgate.db.ApiTokens
import dev.liftgate.db.Builds
import dev.liftgate.db.Db
import dev.liftgate.db.Environments
import dev.liftgate.db.GitConnections
import dev.liftgate.db.Identities
import dev.liftgate.db.Memberships
import dev.liftgate.db.Organizations
import dev.liftgate.db.Projects
import dev.liftgate.db.Services
import dev.liftgate.db.Sessions
import dev.liftgate.db.Users
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.conflict
import dev.liftgate.http.invalid
import dev.liftgate.org.DEFAULT_PLAN
import dev.liftgate.org.Organization
import dev.liftgate.org.Plans
import dev.liftgate.org.User
import dev.liftgate.org.UserStatus
import dev.liftgate.org.toOrganization
import dev.liftgate.org.toUser
import dev.liftgate.service.orgServiceIds
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.UUID

private val usage = """
    usage: admin <command>
      list-pending
      approve <user>
      suspend <org> <reason>
      unsuspend <org>
      suspend-user <user> <reason>
      unsuspend-user <user>
      plan <org> <plan>
    <user> is a user id, login or email
""".trimIndent()

/**
 * @author Dean
 * @date 9/27/2026
 */
class Admin(
    private val db: Db,
    private val plans: Plans = Plans(),
    private val mailer: Mailer? = null,
    private val operators: List<String> = emptyList(),
    private val dashboardUrl: String = "",
) {
    private val log = LoggerFactory.getLogger(Admin::class.java)
    private val planNames = (plans.all.keys + DEFAULT_PLAN).toList()

    suspend fun run(args: List<String>): String {
        val target by lazy { args.getOrNull(1) ?: error(usage) }
        val reason by lazy { args.drop(2).joinToString(" ").ifEmpty { error(usage) } }
        return try {
            when (args.firstOrNull()) {
                "list-pending" -> db.tx { pending() }
                "approve" -> approve(target)
                "suspend" -> suspendOrg(target, reason)
                "unsuspend" -> unsuspendOrg(target)
                "suspend-user" -> suspendUser(target, reason)
                "unsuspend-user" -> unsuspendUser(target)
                "plan" -> setPlan(target, args.getOrNull(2) ?: error(usage))
                else -> error(usage)
            }
        } catch (e: LiftgateException) {
            error(e.message)
        }
    }

    suspend fun approve(ref: String, actor: UUID? = null): String {
        val user = setStatus(ref, setOf(UserStatus.PENDING), UserStatus.ACTIVE, "user.approve", actor)
        return notify("${user.login} is active", listOfNotNull(user.email), "Your Liftgate account is approved", "Your Liftgate account is approved. Sign in to create your first organization.")
    }

    suspend fun suspendUser(ref: String, reason: String, actor: UUID? = null): String {
        val user = setStatus(ref, setOf(UserStatus.PENDING, UserStatus.ACTIVE), UserStatus.SUSPENDED, "user.suspend", actor, reason)
        return notify("${user.login} is suspended", listOfNotNull(user.email), "Your Liftgate account is suspended", "Your Liftgate account is suspended: $reason")
    }

    suspend fun unsuspendUser(ref: String, actor: UUID? = null) = "${setStatus(ref, setOf(UserStatus.SUSPENDED), UserStatus.ACTIVE, "user.unsuspend", actor).login} is active"

    suspend fun suspendOrg(slug: String, reason: String, actor: UUID? = null): String {
        val owners = db.tx {
            val org = org(slug)
            val suspended = Organizations.update({ (Organizations.id eq org.id) and Organizations.suspendedAt.isNull() }) {
                it[suspendedAt] = now()
                it[suspendedReason] = reason
            }
            if (suspended == 0) conflict("$slug is already suspended")
            Builds.update({ (Builds.status eq BuildStatus.QUEUED.sql) and (Builds.serviceId inSubQuery orgServiceIds(org.id)) }) {
                it[status] = BuildStatus.CANCELLED.sql
                it[finishedAt] = now()
            }
            record(Subject.ORG_SUSPENDED, "org.suspend", "org", org.id, actor, mapOf("reason" to reason))
            (Memberships innerJoin Users).select(Users.email)
                .where { (Memberships.orgId eq org.id) and (Memberships.role eq OrgRole.OWNER.sql) }
                .mapNotNull { it[Users.email] }
        }
        return notify("$slug is suspended", owners, "$slug is suspended on Liftgate", "Your organization $slug is suspended and its apps are stopped: $reason")
    }

    suspend fun unsuspendOrg(slug: String, actor: UUID? = null) = db.tx {
        val org = org(slug)
        val resumed = Organizations.update({ (Organizations.id eq org.id) and Organizations.suspendedAt.isNotNull() }) {
            it[suspendedAt] = null
            it[suspendedReason] = null
        }
        if (resumed == 0) conflict("$slug is not suspended")
        record(Subject.ORG_UNSUSPENDED, "org.unsuspend", "org", org.id, actor, emptyMap())
        "$slug is active"
    }

    suspend fun setPlan(slug: String, plan: String, actor: UUID? = null) = db.tx {
        if (plan !in planNames) invalid("$plan is not a plan, choose one of ${planNames.joinToString()}", "plan")
        val org = org(slug)
        Organizations.update({ Organizations.id eq org.id }) { it[Organizations.plan] = plan }
        record(Subject.ORG_PLAN_CHANGED, "org.plan", "org", org.id, actor, mapOf("plan" to plan))
        "$slug is on the ${plans.name(plan)} plan"
    }

    suspend fun isOperator(userId: UUID): Boolean = db.tx { !Users.select(Users.id).where { (Users.id eq userId) and operator() }.empty() }

    suspend fun announce(userId: UUID) {
        if (mailer == null || operators.isEmpty()) return
        val (user, to) = db.tx {
            Users.selectAll().where { (Users.id eq userId) and (Users.status eq UserStatus.PENDING.sql) }.singleOrNull()?.toUser()?.let { user ->
                user to Users.select(Users.email).where { (Users.emailVerified eq true) and operator() }.mapNotNull { it[Users.email] }
            }
        } ?: return
        val text = "${user.login}${user.email?.let { " ($it)" }.orEmpty()} signed up and is waiting for approval. Approve or suspend the account at $dashboardUrl/dashboard/operator"
        log.info(notify("emailed ${to.size} operators about ${user.login}", to, "A Liftgate account is waiting for approval", text))
    }

    suspend fun summary() = OperatorSummary(db.tx { Users.selectAll().where { Users.status eq UserStatus.PENDING.sql }.count() }, planNames)

    suspend fun users(status: UserStatus?, before: UUID?, limit: Int): List<OperatorUser> = db.tx {
        val pending = Users.status eq UserStatus.PENDING.sql
        val query = Users.selectAll().orderBy(pending to SortOrder.DESC, Users.createdAt to SortOrder.DESC, Users.id to SortOrder.DESC).limit(limit)
        status?.let { wanted -> query.andWhere { Users.status eq wanted.sql } }
        before?.let { id ->
            val cursor = Users.select(Users.status, Users.createdAt).where { Users.id eq id }.singleOrNull() ?: invalid("before must be a user id", "before")
            val older = older(Users.createdAt, Users.id, cursor[Users.createdAt], id)
            query.andWhere {
                when {
                    status != null -> older
                    cursor[Users.status] == UserStatus.PENDING.sql -> not(pending) or (pending and older)
                    else -> not(pending) and older
                }
            }
        }
        val rows = query.toList()
        val ids = rows.map { it[Users.id] }
        val providers = Identities.select(Identities.userId, Identities.provider).where { Identities.userId inList ids }.groupBy({ it[Identities.userId] }, { it[Identities.provider] })
        val orgs = counts(Memberships.userId, Memberships, Memberships.userId inList ids)
        rows.map { OperatorUser(it.toUser(), providers[it[Users.id]].orEmpty().distinct().sorted(), orgs[it[Users.id]] ?: 0, it[Users.createdAt].toInstant()) }
    }

    suspend fun orgs(before: UUID?, limit: Int): List<OperatorOrg> = db.tx {
        val query = Organizations.selectAll().orderBy(Organizations.createdAt to SortOrder.DESC, Organizations.id to SortOrder.DESC).limit(limit)
        before?.let { id ->
            val createdAt = Organizations.select(Organizations.createdAt).where { Organizations.id eq id }.singleOrNull()?.get(Organizations.createdAt)
                ?: invalid("before must be an organization id", "before")
            query.andWhere { older(Organizations.createdAt, Organizations.id, createdAt, id) }
        }
        val rows = query.toList()
        val ids = rows.map { it[Organizations.id] }
        val members = counts(Memberships.orgId, Memberships, Memberships.orgId inList ids)
        val projects = counts(Projects.orgId, Projects, Projects.orgId inList ids)
        val services = counts(Projects.orgId, Services innerJoin Environments innerJoin Projects, Projects.orgId inList ids)
        rows.map { row ->
            val id = row[Organizations.id]
            OperatorOrg(row.toOrganization(), members[id] ?: 0, projects[id] ?: 0, services[id] ?: 0, row[Organizations.createdAt].toInstant())
        }
    }

    private fun pending() = Users.selectAll().where { Users.status eq UserStatus.PENDING.sql }.orderBy(Users.createdAt)
        .joinToString("\n") { listOf(it[Users.id], it[Users.login], it[Users.email] ?: "-", it[Users.createdAt].toInstant()).joinToString("  ") }
        .ifEmpty { "no pending users" }

    private suspend fun setStatus(ref: String, from: Set<UserStatus>, to: UserStatus, action: String, actor: UUID?, reason: String? = null): User = db.tx {
        val user = user(ref)
        if (Users.update({ (Users.id eq user.id) and (Users.status inList from.map { it.sql }) }) { it[Users.status] = to.sql } == 0) conflict("${user.login} is ${user.status.sql}")
        if (to == UserStatus.SUSPENDED) {
            Sessions.deleteWhere { Sessions.userId eq user.id }
            ApiTokens.deleteWhere { ApiTokens.createdBy eq user.id }
        }
        record(Subject.USER_UPDATED, action, "user", user.id, actor, mapOf("status" to to.sql) + listOfNotNull(reason?.let { "reason" to it }))
        user
    }

    private suspend fun notify(result: String, to: List<String>, subject: String, text: String): String {
        val mailer = mailer ?: return result
        val failed = to.mapNotNull { address ->
            runCatching { withContext(Dispatchers.IO) { mailer.send(address, subject, text) } }.exceptionOrNull()?.let { "$address (${it.message})" }
        }
        return if (failed.isEmpty()) result else "$result, but the notice email failed for ${failed.joinToString()}"
    }

    private fun operator(): Op<Boolean> {
        val (logins, emails) = operators.partition { it.startsWith("github:") }
        val email = exists(
            Identities.select(Identities.id)
                .where { (Identities.userId eq Users.id) and (Identities.emailVerified eq true) and (Identities.email.lowerCase() inList emails) },
        )
        val github = exists(
            GitConnections.select(GitConnections.userId)
                .where { (GitConnections.userId eq Users.id) and (GitConnections.provider eq GITHUB) and (GitConnections.accountLogin.lowerCase() inList logins.map { it.removePrefix("github:") }) },
        )
        return (Users.status eq UserStatus.ACTIVE.sql) and (email or github)
    }

    private fun older(createdAt: Column<OffsetDateTime>, id: Column<UUID>, at: OffsetDateTime, cursor: UUID) = (createdAt less at) or ((createdAt eq at) and (id less cursor))

    private fun <K : Any> counts(key: Column<K>, from: ColumnSet, where: Op<Boolean>): Map<K, Long> {
        val count = key.count()
        return from.select(key, count).where(where).groupBy(key).associate { it[key] to it[count] }
    }

    private fun org(slug: String): Organization =
        Organizations.selectAll().where { Organizations.slug eq slug }.singleOrNull()?.toOrganization() ?: missing("no organization $slug")

    private fun user(ref: String): User {
        val id = runCatching { UUID.fromString(ref) }.getOrNull()
        val matches = Users.selectAll()
            .where { if (id != null) Users.id eq id else (Users.login.lowerCase() eq ref.lowercase()) or (Users.email.lowerCase() eq ref.lowercase()) }
            .map { it.toUser() }
        return matches.singleOrNull() ?: if (matches.isEmpty()) missing("no user matches $ref") else conflict("${matches.size} users match $ref, use the id")
    }

    private fun missing(message: String): Nothing = throw LiftgateException(HttpStatusCode.NotFound, "not_found", message)

    private fun JdbcTransaction.record(subject: Subject, action: String, target: String, id: UUID, actor: UUID?, details: Map<String, String>) {
        enqueue(subject, JsonObject(details.mapValues { JsonPrimitive(it.value) } + ("${target}Id" to JsonPrimitive(id.toString()))))
        audit(actor, action, target, id.toString(), details)
    }
}
