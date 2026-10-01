@file:UseSerializers(UuidSerializer::class)

package dev.liftgate.database

import dev.liftgate.http.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

/**
 * @author Dean
 * @date 9/30/2026
 */
@Serializable
data class ServiceLink(val serviceId: UUID, val envName: String = "DATABASE_URL")
