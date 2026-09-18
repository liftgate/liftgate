package dev.liftgate.auth

import dev.liftgate.http.forbidden
import dev.liftgate.org.Orgs
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
class Access(private val orgs: Orgs) {
    suspend fun require(orgId: UUID, principal: Principal, min: OrgRole = OrgRole.MEMBER) {
        if (principal.orgId != null && principal.orgId != orgId) forbidden()
        val role = orgs.role(orgId, principal.user.id) ?: forbidden()
        if (role.ordinal > min.ordinal) forbidden()
    }
}
