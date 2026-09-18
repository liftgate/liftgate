@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package dev.liftgate.domain

import dev.liftgate.http.InstantSerializer
import dev.liftgate.http.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
enum class DomainKind {
    @SerialName("platform") PLATFORM,
    @SerialName("custom") CUSTOM,
}

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class Domain(
    val id: UUID,
    val serviceId: UUID,
    val hostname: String,
    val kind: DomainKind,
    val verificationToken: String?,
    val verifiedAt: Instant?,
    val certificateStatus: String,
)
