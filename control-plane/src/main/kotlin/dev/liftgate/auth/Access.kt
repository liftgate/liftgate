package dev.liftgate.auth

import dev.liftgate.http.accountPending
import dev.liftgate.http.forbidden
import dev.liftgate.http.orgSuspended
import dev.liftgate.org.Orgs
import dev.liftgate.org.UserStatus
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
class Access(private val orgs: Orgs) {
    suspend fun require(orgId: UUID, principal: Principal, min: OrgRole = OrgRole.MEMBER) {
        if (principal.orgId != null && principal.orgId != orgId) forbidden()
        if (principal.user.status == UserStatus.PENDING) accountPending()
        val role = orgs.role(orgId, principal.user.id) ?: forbidden()
        if (role.ordinal > min.ordinal) forbidden()
        if (min != OrgRole.MEMBER && orgs.suspended(orgId)) orgSuspended()
    }
}
