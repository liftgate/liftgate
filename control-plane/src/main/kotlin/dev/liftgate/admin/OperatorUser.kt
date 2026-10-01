@file:UseSerializers(InstantSerializer::class)

package dev.liftgate.admin

import dev.liftgate.http.InstantSerializer
import dev.liftgate.org.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * @author Dean
 * @date 9/30/2026
 */
@Serializable
data class OperatorUser(val user: User, val providers: List<String>, val orgs: Long, val createdAt: Instant)
