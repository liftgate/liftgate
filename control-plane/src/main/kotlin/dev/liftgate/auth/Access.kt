package dev.liftgate.auth

import dev.liftgate.http.accountPending
import dev.liftgate.http.forbidden
import dev.liftgate.http.orgSuspended
import dev.liftgate.org.Organization
import dev.liftgate.org.Orgs
import dev.liftgate.org.UserStatus
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
class Access(private val orgs: Orgs) {
    suspend fun require(orgId: UUID, principal: Principal, min: OrgRole = OrgRole.MEMBER) = require(orgs.byId(orgId, principal.user.id) ?: forbidden(), principal, min)

    fun require(org: Organization, principal: Principal, min: OrgRole = OrgRole.MEMBER) {
        if (principal.orgId != null && principal.orgId != org.id) forbidden()
        if (principal.user.status == UserStatus.PENDING) accountPending()
        val role = org.role ?: forbidden()
        if (role.ordinal > min.ordinal) forbidden()
        if (min != OrgRole.MEMBER && org.suspendedAt != null) orgSuspended()
    }
}
