package io.liftgate.server.models

import kotlinx.serialization.Serializable

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
@Serializable
data class Resource(
    val id: String, val version: String,
    val replacements: Map<String, String>,
    val templateDirectory: String
)
