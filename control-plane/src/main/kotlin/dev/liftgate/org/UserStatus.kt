package dev.liftgate.org

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
enum class UserStatus {
    @SerialName("pending") PENDING,
    @SerialName("active") ACTIVE,
    @SerialName("suspended") SUSPENDED,
}
