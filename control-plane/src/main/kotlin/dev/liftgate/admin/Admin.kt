package dev.liftgate.admin

import dev.liftgate.db.ApiTokens
import dev.liftgate.db.AuditLog
import dev.liftgate.db.Builds
import dev.liftgate.db.Db
import dev.liftgate.db.Organizations
import dev.liftgate.db.Sessions
import dev.liftgate.db.Users
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.org.Organization
import dev.liftgate.org.User
import dev.liftgate.org.UserStatus
import dev.liftgate.org.toOrganization
import dev.liftgate.org.toUser
import dev.liftgate.service.orgServiceIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

private val usage = """
    usage: admin <command>
      list-pending
      approve <user>
      suspend <org> <reason>
      unsuspend <org>
      suspend-user <user> <reason>
      unsuspend-user <user>
    <user> is a user id, login or email
""".trimIndent()

/**
 * @author Dean
 * @date 9/27/2026
 */
class Admin(private val db: Db) {
    suspend fun run(args: List<String>): String {
        val target by lazy { args.getOrNull(1) ?: error(usage) }
        val reason by lazy { args.drop(2).joinToString(" ").ifEmpty { error(usage) } }
        return db.tx {
            when (args.firstOrNull()) {
                "list-pending" -> pending()
                "approve" -> setStatus(target, setOf(UserStatus.PENDING), UserStatus.ACTIVE, "user.approve")
                "suspend" -> suspendOrg(target, reason)
                "unsuspend" -> unsuspendOrg(target)
                "suspend-user" -> setStatus(target, setOf(UserStatus.PENDING, UserStatus.ACTIVE), UserStatus.SUSPENDED, "user.suspend", reason)
                "unsuspend-user" -> setStatus(target, setOf(UserStatus.SUSPENDED), UserStatus.ACTIVE, "user.unsuspend")
                else -> error(usage)
            }
        }
    }

    private fun pending() = Users.selectAll().where { Users.status eq UserStatus.PENDING.sql }.orderBy(Users.createdAt)
        .joinToString("\n") { listOf(it[Users.id], it[Users.login], it[Users.email] ?: "-", it[Users.createdAt].toInstant()).joinToString("  ") }
        .ifEmpty { "no pending users" }

    private fun JdbcTransaction.setStatus(ref: String, from: Set<UserStatus>, to: UserStatus, action: String, reason: String? = null): String {
        val user = user(ref)
        if (user.status !in from) error("${user.login} is ${user.status.sql}")
        Users.update({ Users.id eq user.id }) { it[Users.status] = to.sql }
        if (to == UserStatus.SUSPENDED) {
            Sessions.deleteWhere { Sessions.userId eq user.id }
            ApiTokens.deleteWhere { ApiTokens.createdBy eq user.id }
        }
        record(Subject.USER_UPDATED, action, "user", user.id, mapOf("status" to to.sql) + listOfNotNull(reason?.let { "reason" to it }))
        return "${user.login} is ${to.sql}"
    }

    private fun JdbcTransaction.suspendOrg(slug: String, reason: String): String {
        val org = org(slug)
        if (org.suspendedAt != null) error("$slug is already suspended")
        Organizations.update({ Organizations.id eq org.id }) {
            it[suspendedAt] = now()
            it[suspendedReason] = reason
        }
        Builds.update({ (Builds.status eq BuildStatus.QUEUED.sql) and (Builds.serviceId inSubQuery orgServiceIds(org.id)) }) {
            it[status] = BuildStatus.CANCELLED.sql
            it[finishedAt] = now()
        }
        record(Subject.ORG_SUSPENDED, "org.suspend", "org", org.id, mapOf("reason" to reason))
        return "$slug is suspended"
    }

    private fun JdbcTransaction.unsuspendOrg(slug: String): String {
        val org = org(slug)
        if (org.suspendedAt == null) error("$slug is not suspended")
        Organizations.update({ Organizations.id eq org.id }) {
            it[suspendedAt] = null
            it[suspendedReason] = null
        }
        record(Subject.ORG_UNSUSPENDED, "org.unsuspend", "org", org.id, emptyMap())
        return "$slug is active"
    }

    private fun org(slug: String): Organization =
        Organizations.selectAll().where { Organizations.slug eq slug }.singleOrNull()?.toOrganization() ?: error("no organization $slug")

    private fun user(ref: String): User {
        val id = runCatching { UUID.fromString(ref) }.getOrNull()
        val matches = Users.selectAll()
            .where { if (id != null) Users.id eq id else (Users.login.lowerCase() eq ref.lowercase()) or (Users.email.lowerCase() eq ref.lowercase()) }
            .map { it.toUser() }
        return matches.singleOrNull() ?: error(if (matches.isEmpty()) "no user matches $ref" else "${matches.size} users match $ref, use the id")
    }

    private fun JdbcTransaction.record(subject: Subject, action: String, target: String, id: UUID, details: Map<String, String>) {
        val fields = details.mapValues { JsonPrimitive(it.value) }
        enqueue(subject, JsonObject(fields + ("${target}Id" to JsonPrimitive(id.toString()))))
        AuditLog.insert {
            it[AuditLog.action] = action
            it[targetType] = target
            it[targetId] = id.toString()
            it[AuditLog.details] = JsonObject(fields)
        }
    }
}
