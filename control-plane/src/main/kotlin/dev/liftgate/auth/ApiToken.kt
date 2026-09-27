@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package dev.liftgate.auth

import dev.liftgate.http.InstantSerializer
import dev.liftgate.http.UuidSerializer
import dev.liftgate.org.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
data class ApiToken(val id: UUID, val name: String, val createdBy: User, val createdAt: Instant, val lastUsedAt: Instant?, val expiresAt: Instant?)
