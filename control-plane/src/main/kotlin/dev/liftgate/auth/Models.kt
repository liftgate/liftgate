package dev.liftgate.auth

import dev.liftgate.org.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
enum class OrgRole {
    @SerialName("owner") OWNER,
    @SerialName("admin") ADMIN,
    @SerialName("member") MEMBER,
}

/**
 * @author Dean
 * @date 9/17/2026
 */
data class Principal(val user: User, val token: Boolean, val orgId: UUID? = null)

private val random = SecureRandom()

fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
