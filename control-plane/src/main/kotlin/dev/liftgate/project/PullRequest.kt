@file:UseSerializers(InstantSerializer::class)

package dev.liftgate.project

import dev.liftgate.http.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * @author Dean
 * @date 9/30/2026
 */
@Serializable
data class PullRequest(
    val number: Int,
    val title: String,
    val headRef: String,
    val headSha: String,
    val fork: Boolean,
    val approvedSha: String? = null,
    val error: String? = null,
    val updatedAt: Instant = Instant.now(),
    @Transient val commentId: Long? = null,
) {
    val trusted get() = !fork || approvedSha == headSha
}
