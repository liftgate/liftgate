@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package dev.liftgate.org

import dev.liftgate.auth.OrgRole
import dev.liftgate.http.InstantSerializer
import dev.liftgate.http.UuidSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class User(val id: UUID, val login: String, val name: String?, val email: String?, val avatarUrl: String?, val status: UserStatus = UserStatus.ACTIVE, @EncodeDefault(EncodeDefault.Mode.NEVER) val operator: Boolean? = null)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class Organization(
    val id: UUID,
    val slug: String,
    val name: String,
    val plan: String,
    val suspendedAt: Instant? = null,
    val suspendedReason: String? = null,
    val role: OrgRole? = null,
)
