@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package dev.liftgate.database

import dev.liftgate.http.InstantSerializer
import dev.liftgate.http.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

/**
 * @author Dean
 * @date 9/30/2026
 */
@Serializable
data class Database(
    val id: UUID,
    val environmentId: UUID,
    val slug: String,
    val storageGb: Int,
    val cpuMillis: Int,
    val memoryMb: Int,
    val restoredFrom: UUID?,
    val restoreTarget: Instant?,
    val createdAt: Instant,
    val links: List<ServiceLink> = emptyList(),
    val ready: Boolean = false,
)
