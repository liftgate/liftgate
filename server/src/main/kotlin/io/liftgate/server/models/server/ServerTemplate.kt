package io.liftgate.server.models.server

import io.liftgate.server.models.ResourceReference
import io.liftgate.server.resource.ResourceHandler
import kotlinx.serialization.Serializable

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
@Serializable
data class ServerTemplate(
    val id: String,
    val autoScalePortStart: Int,
    val autoScalePropertyChoiceScheme: String,
    val dependencies: List<ResourceReference>,
    val replacementFiles: List<String>,
    val resources: ServerResources,
    val replacements: Map<String, String>,
    val executions: ServerStartStop
)
{
    fun handleReplacements(original: String): String
    {
        var replaced = original

        replacements
            .forEach { (key, value) ->
                replaced = replaced
                    .replace("<${key}>", value)
            }

        dependencies.forEach {
            val resource = ResourceHandler
                .findResourceByReference(it)
                ?: return@forEach

            resource.replacements
                .forEach { (key, value) ->
                    replaced = replaced
                        .replace("<${key}>", value)
                }
        }

        replaced = replaced.replace(
            "<resources.memory>", this
                .resources.memory.toString()
        )

        return replaced
    }
}
