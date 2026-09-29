package dev.liftgate.org

import dev.liftgate.auth.OrgRole
import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
data class InvitationPreview(val slug: String, val name: String, val role: OrgRole, val invitedBy: String)
