@file:UseSerializers(InstantSerializer::class)

package dev.liftgate.org

import dev.liftgate.http.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
data class CreatedInvitation(val url: String, val expiresAt: Instant, val emailed: Boolean)
