@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package dev.liftgate.deploy

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
enum class BuildStatus {
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
}

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class Build(
    val id: UUID,
    val serviceId: UUID,
    val commitSha: String,
    val commitMessage: String?,
    val branch: String,
    val status: BuildStatus,
    val imageRef: String?,
    val error: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val createdAt: Instant,
)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
enum class DeploymentStatus {
    @SerialName("pending") PENDING,
    @SerialName("releasing") RELEASING,
    @SerialName("running") RUNNING,
    @SerialName("failed") FAILED,
    @SerialName("superseded") SUPERSEDED,
    @SerialName("rolled_back") ROLLED_BACK;

    fun allows(to: DeploymentStatus) = to == this || to in when (this) {
        PENDING -> setOf(RELEASING, RUNNING, FAILED, SUPERSEDED)
        RELEASING -> setOf(RUNNING, FAILED, SUPERSEDED)
        RUNNING -> setOf(FAILED, SUPERSEDED, ROLLED_BACK)
        FAILED -> setOf(RUNNING)
        SUPERSEDED, ROLLED_BACK -> emptySet()
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class Deployment(
    val id: UUID,
    val serviceId: UUID,
    val buildId: UUID,
    val status: DeploymentStatus,
    val replicasReady: Int,
    val error: String?,
    val createdAt: Instant,
)
