@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package dev.liftgate.notify

import dev.liftgate.http.InstantSerializer
import dev.liftgate.http.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
data class NotificationChannel(
    val id: UUID,
    val name: String,
    val kind: Kind,
    val host: String,
    val events: Set<Event>,
    val createdAt: Instant,
    val secret: String? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("webhook") WEBHOOK,
        @SerialName("slack") SLACK,
        @SerialName("discord") DISCORD,
    }

    @Serializable
    enum class Event {
        @SerialName("build_failed") BUILD_FAILED,
        @SerialName("deployment_running") DEPLOYMENT_RUNNING,
        @SerialName("deployment_failed") DEPLOYMENT_FAILED,
    }
}
