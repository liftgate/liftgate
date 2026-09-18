@file:UseSerializers(UuidSerializer::class)

package dev.liftgate.org

import dev.liftgate.http.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class User(val id: UUID, val githubId: Long, val login: String, val name: String?, val email: String?, val avatarUrl: String?)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class Organization(val id: UUID, val slug: String, val name: String, val plan: String)
