@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package dev.liftgate.notify

import dev.liftgate.http.InstantSerializer
import dev.liftgate.http.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
data class Notification(
    val event: String,
    val text: String,
    val url: String,
    val org: String,
    val project: String? = null,
    val environment: String? = null,
    val service: String? = null,
    val commitSha: String? = null,
    val buildId: UUID? = null,
    val deploymentId: UUID? = null,
    val at: Instant = Instant.now(),
)
