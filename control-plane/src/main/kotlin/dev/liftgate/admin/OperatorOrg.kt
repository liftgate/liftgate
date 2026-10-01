@file:UseSerializers(InstantSerializer::class)

package dev.liftgate.admin

import dev.liftgate.http.InstantSerializer
import dev.liftgate.org.Organization
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * @author Dean
 * @date 9/30/2026
 */
@Serializable
data class OperatorOrg(val org: Organization, val members: Long, val projects: Long, val services: Long, val createdAt: Instant)
