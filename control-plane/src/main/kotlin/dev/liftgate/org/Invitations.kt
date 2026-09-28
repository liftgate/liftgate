package dev.liftgate.org

import dev.liftgate.auth.ApiTokens
import dev.liftgate.auth.Mailer
import dev.liftgate.auth.OrgRole
import dev.liftgate.auth.randomToken
import dev.liftgate.db.Db
import dev.liftgate.db.Invitations as InvitationsTable
import dev.liftgate.db.Memberships
import dev.liftgate.db.Organizations
import dev.liftgate.db.Users
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.conflict
import dev.liftgate.http.forbidden
import dev.liftgate.http.notFound
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.UUID

private const val INVITATION_DAYS = 7L
private val log = LoggerFactory.getLogger(Invitations::class.java)

private fun ResultRow.invitedRole(): OrgRole = this[InvitationsTable.role].toEnum()

private fun gone(): Nothing = throw LiftgateException(HttpStatusCode.Gone, "invitation_gone", "this invitation has expired, was already used, or its sender can no longer invite")

/**
 * @author Dean
 * @date 9/27/2026
 */
class Invitations(private val db: Db, private val mailer: Mailer?, private val dashboardUrl: String) {
    suspend fun create(org: Organization, inviter: User, role: OrgRole, email: String?): CreatedInvitation {
        val token = randomToken()
        val expiresAt = now().plusDays(INVITATION_DAYS)
        db.tx {
            if (role.ordinal < (memberRole(org.id, inviter.id) ?: forbidden()).ordinal) forbidden()
            InvitationsTable.insert {
                it[id] = UUID.randomUUID()
                it[orgId] = org.id
                it[InvitationsTable.email] = email
                it[InvitationsTable.role] = role.sql
                it[tokenHash] = ApiTokens.hash(token)
                it[createdBy] = inviter.id
                it[InvitationsTable.expiresAt] = expiresAt
            }
        }
        val url = "$dashboardUrl/account/invitations/$token"
        val text = "${inviter.login} invited you to join ${org.name} on Liftgate as ${role.sql}.\n\nAccept the invitation: $url\n\n" +
            "The link works once and expires in $INVITATION_DAYS days. If you were not expecting it, you can ignore this email.\n"
        val emailed = email != null &&
            runCatching { withContext(Dispatchers.IO) { mailer?.send(email, "Join ${org.name} on Liftgate", text) } != null }
                .onFailure { log.warn("could not email an invitation to {}", email, it) }.getOrDefault(false)
        return CreatedInvitation(url, expiresAt.toInstant(), emailed)
    }

    suspend fun preview(token: String): InvitationPreview = db.tx {
        open(token).let { InvitationPreview(it[Organizations.slug], it[Organizations.name], it.invitedRole(), it[Users.login]) }
    }

    suspend fun accept(token: String, userId: UUID): Organization = db.tx {
        val invitation = open(token)
        val org = invitation.toOrganization()
        if (InvitationsTable.update({ (InvitationsTable.id eq invitation[InvitationsTable.id]) and InvitationsTable.acceptedAt.isNull() }) { it[acceptedAt] = now() } == 0) gone()
        val joined = Memberships.insertIgnore {
            it[orgId] = org.id
            it[Memberships.userId] = userId
            it[role] = invitation[InvitationsTable.role]
        }.insertedCount
        if (joined == 0) conflict("you are already a member of ${org.slug}")
        org.copy(role = invitation.invitedRole())
    }

    private fun open(token: String): ResultRow {
        val row = (InvitationsTable innerJoin Organizations innerJoin Users).selectAll().where { InvitationsTable.tokenHash eq ApiTokens.hash(token) }.singleOrNull()
            ?: notFound("invitation")
        val inviter = memberRole(row[InvitationsTable.orgId], row[InvitationsTable.createdBy])
        if (row[InvitationsTable.acceptedAt] != null || !row[InvitationsTable.expiresAt].isAfter(now()) || inviter == null || inviter.ordinal > row.invitedRole().ordinal) gone()
        return row
    }
}
