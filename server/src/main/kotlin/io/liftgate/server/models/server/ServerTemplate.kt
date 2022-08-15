package io.liftgate.server.models.server

import io.liftgate.server.models.ResourceReference
import kotlinx.serialization.Serializable

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
@Serializable
data class ServerTemplate(
    val id: String,
    val autoScalePropertyChoiceScheme: String,
    val dependencies: List<ResourceReference>,
    val resources: ServerResources,
    val replacements: Map<String, String>,
    val executions: ServerStartStop
)
