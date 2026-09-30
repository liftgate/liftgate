package dev.liftgate.org

import dev.liftgate.auth.OrgRole
import dev.liftgate.build.orphanRepositories
import dev.liftgate.db.ApiTokens
import dev.liftgate.db.AuditLog
import dev.liftgate.db.Db
import dev.liftgate.db.Memberships
import dev.liftgate.db.Organizations
import dev.liftgate.db.Projects
import dev.liftgate.db.Users
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.conflict
import dev.liftgate.http.notFound
import dev.liftgate.http.orgSuspended
import dev.liftgate.project.enqueueTeardown
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

fun ResultRow.toUser() = User(this[Users.id], this[Users.login], this[Users.name], this[Users.email], this[Users.avatarUrl], this[Users.status].toEnum())

fun ResultRow.toOrganization() = Organization(
    this[Organizations.id],
    this[Organizations.slug],
    this[Organizations.name],
    this[Organizations.plan],
    this[Organizations.suspendedAt]?.toInstant(),
    this[Organizations.suspendedReason],
    getOrNull(Memberships.role)?.toEnum(),
)

fun ResultRow.toRole(): OrgRole = this[Memberships.role].toEnum()

fun ColumnSet.withRole(userId: UUID?): ColumnSet = userId?.let { join(Memberships, JoinType.LEFT, Organizations.id, Memberships.orgId) { Memberships.userId eq it } } ?: this

fun memberRole(orgId: UUID, userId: UUID): OrgRole? =
    Memberships.select(Memberships.role).where { (Memberships.orgId eq orgId) and (Memberships.userId eq userId) }.singleOrNull()?.toRole()

fun insertUser(
    login: String,
    name: String?,
    email: String?,
    avatarUrl: String?,
    status: UserStatus = UserStatus.ACTIVE,
    termsAcceptedAt: OffsetDateTime? = null,
): User = Users.insertReturning {
    it[id] = UUID.randomUUID()
    it[Users.login] = login
    it[Users.name] = name
    it[Users.email] = email
    it[emailVerified] = email != null
    it[Users.avatarUrl] = avatarUrl
    it[Users.status] = status.sql
    it[Users.termsAcceptedAt] = termsAcceptedAt
}.single().toUser()

/**
 * @author Dean
 * @date 9/17/2026
 */
class Orgs(private val db: Db, private val limits: Limits = Limits()) {
    suspend fun user(id: UUID): User? = db.tx { Users.selectAll().where { Users.id eq id }.singleOrNull()?.toUser() }

    suspend fun create(slug: String, name: String, owner: UUID): Organization = db.tx {
        limits.ownedOrgs(owner)
        val org = Organizations.insertReturning {
            it[id] = UUID.randomUUID()
            it[Organizations.slug] = slug
            it[Organizations.name] = name
        }.single().toOrganization()
        Memberships.insert {
            it[orgId] = org.id
            it[userId] = owner
            it[role] = OrgRole.OWNER.sql
        }
        org
    }

    suspend fun bySlug(slug: String, userId: UUID? = null): Organization? = db.tx {
        Organizations.withRole(userId).selectAll().where { Organizations.slug eq slug }.singleOrNull()?.toOrganization()
    }

    suspend fun byId(id: UUID, userId: UUID): Organization? = db.tx {
        Organizations.withRole(userId).selectAll().where { Organizations.id eq id }.singleOrNull()?.toOrganization()
    }

    suspend fun forUser(userId: UUID): List<Organization> = db.tx {
        (Organizations innerJoin Memberships).selectAll().where { Memberships.userId eq userId }.orderBy(Organizations.slug).map { it.toOrganization() }
    }

    suspend fun members(orgId: UUID): List<Pair<User, OrgRole>> = db.tx {
        (Memberships innerJoin Users).selectAll().where { Memberships.orgId eq orgId }.orderBy(Users.login).map { it.toUser() to it.toRole() }
    }

    suspend fun setRole(orgId: UUID, userId: UUID, role: OrgRole) = db.tx {
        if (role == OrgRole.OWNER && memberRole(orgId, userId) != OrgRole.OWNER) limits.ownedOrgs(userId)
        changeMembers(orgId) { Memberships.update({ (Memberships.orgId eq orgId) and (Memberships.userId eq userId) }) { it[Memberships.role] = role.sql } }
    }

    suspend fun removeMember(orgId: UUID, userId: UUID) = db.tx {
        changeMembers(orgId) { Memberships.deleteWhere { (Memberships.orgId eq orgId) and (Memberships.userId eq userId) } }
        ApiTokens.deleteWhere { (ApiTokens.orgId eq orgId) and (createdBy eq userId) }
    }

    suspend fun audit(orgId: UUID, before: Long?, limit: Int): List<AuditEntry> = db.tx {
        (AuditLog leftJoin Users).selectAll().where { (AuditLog.orgId eq orgId) and (AuditLog.id less (before ?: Long.MAX_VALUE)) }
            .orderBy(AuditLog.id, SortOrder.DESC).limit(limit)
            .map {
                AuditEntry(
                    it[AuditLog.id],
                    it[AuditLog.actorUserId]?.let { _ -> it.toUser() },
                    it[AuditLog.viaToken],
                    it[AuditLog.action],
                    it[AuditLog.targetType],
                    it[AuditLog.targetId],
                    it[AuditLog.details],
                    it[AuditLog.createdAt].toInstant(),
                )
            }
    }

    suspend fun usage(orgId: UUID): Usage = db.tx { limits.usage(orgId) }

    suspend fun delete(orgId: UUID) = db.tx { deleteOrgs(listOf(orgId)) }

    suspend fun deleteUser(userId: UUID) = db.tx {
        val owned = (Memberships innerJoin Organizations).selectAll()
            .where { (Memberships.userId eq userId) and (Memberships.role eq OrgRole.OWNER.sql) }
            .map { it.toOrganization() }
        owned.firstOrNull { it.suspendedAt != null }?.let { orgSuspended("${it.slug} is suspended") }
        val ids = owned.map { it.id }
        val shared = (Memberships innerJoin Organizations).select(Organizations.slug)
            .where { (Memberships.orgId inList ids) and (Memberships.userId neq userId) }
            .map { it[Organizations.slug] }.distinct()
        if (shared.isNotEmpty()) conflict("organizations you own with other members must be deleted first: ${shared.joinToString()}")
        deleteOrgs(ids)
        ApiTokens.deleteWhere { ApiTokens.createdBy eq userId }
        Users.deleteWhere { Users.id eq userId }
    }

    private fun changeMembers(orgId: UUID, change: () -> Int) {
        if (Organizations.select(Organizations.suspendedAt).where { Organizations.id eq orgId }.forUpdate(ForUpdateOption.ForUpdate).single()[Organizations.suspendedAt] != null) orgSuspended()
        if (change() == 0) notFound("member")
        if (Memberships.selectAll().where { (Memberships.orgId eq orgId) and (Memberships.role eq OrgRole.OWNER.sql) }.empty()) {
            throw LiftgateException(HttpStatusCode.Conflict, "last_owner", "an organization needs an owner, make someone else an owner first")
        }
    }

    private fun JdbcTransaction.deleteOrgs(ids: List<UUID>) {
        enqueueTeardown { Projects.orgId inList ids }
        orphanRepositories(Projects.orgId inList ids)
        Organizations.deleteWhere { Organizations.id inList ids }
    }
}
