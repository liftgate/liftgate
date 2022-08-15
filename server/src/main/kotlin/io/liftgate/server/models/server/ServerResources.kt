package io.liftgate.server.models.server

import kotlinx.serialization.Serializable

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
@Serializable
data class ServerResources(
    val memory: Int
)
