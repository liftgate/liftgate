@file:UseSerializers(InstantSerializer::class)

package dev.liftgate.org

import dev.liftgate.http.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
data class AuditEntry(
    val id: Long,
    val actor: User?,
    val viaToken: Boolean,
    val action: String,
    val targetType: String,
    val targetId: String,
    val details: JsonObject,
    val createdAt: Instant,
)
