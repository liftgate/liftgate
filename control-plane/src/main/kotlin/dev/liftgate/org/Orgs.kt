package dev.liftgate.org

import dev.liftgate.auth.OrgRole
import dev.liftgate.db.Db
import dev.liftgate.db.Memberships
import dev.liftgate.db.Organizations
import dev.liftgate.db.Users
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

fun ResultRow.toUser() = User(this[Users.id], this[Users.login], this[Users.name], this[Users.email], this[Users.avatarUrl])

fun ResultRow.toOrganization() = Organization(this[Organizations.id], this[Organizations.slug], this[Organizations.name], this[Organizations.plan])

fun ResultRow.toRole(): OrgRole = this[Memberships.role].toEnum()

fun insertUser(login: String, name: String?, email: String?, avatarUrl: String?): User = Users.insertReturning {
    it[id] = UUID.randomUUID()
    it[Users.login] = login
    it[Users.name] = name
    it[Users.email] = email
    it[emailVerified] = email != null
    it[Users.avatarUrl] = avatarUrl
}.single().toUser()

/**
 * @author Dean
 * @date 9/17/2026
 */
class Orgs(private val db: Db) {
    suspend fun user(id: UUID): User? = db.tx { Users.selectAll().where { Users.id eq id }.singleOrNull()?.toUser() }

    suspend fun create(slug: String, name: String, owner: UUID): Organization = db.tx {
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

    suspend fun bySlug(slug: String): Organization? = db.tx {
        Organizations.selectAll().where { Organizations.slug eq slug }.singleOrNull()?.toOrganization()
    }

    suspend fun forUser(userId: UUID): List<Organization> = db.tx {
        (Organizations innerJoin Memberships).selectAll().where { Memberships.userId eq userId }.orderBy(Organizations.slug).map { it.toOrganization() }
    }

    suspend fun members(orgId: UUID): List<Pair<User, OrgRole>> = db.tx {
        (Memberships innerJoin Users).selectAll().where { Memberships.orgId eq orgId }.orderBy(Users.login).map { it.toUser() to it.toRole() }
    }

    suspend fun role(orgId: UUID, userId: UUID): OrgRole? = db.tx {
        Memberships.select(Memberships.role).where { (Memberships.orgId eq orgId) and (Memberships.userId eq userId) }.singleOrNull()?.toRole()
    }
}
