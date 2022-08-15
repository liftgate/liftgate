package io.liftgate.server.resource

import io.liftgate.server.LiftgateEngine
import io.liftgate.server.logger
import io.liftgate.server.models.Resource
import io.liftgate.server.models.ResourceReference
import io.liftgate.server.resources
import io.liftgate.server.startup.StartupStep
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ResourceHandler : StartupStep
{
    fun findResourceByReference(reference: ResourceReference) =
        resources.find {
            it.id == reference.id && it.version == reference.version
        }

    @OptIn(ExperimentalSerializationApi::class)
    override fun perform(context: LiftgateEngine)
    {
        val resourcesDirectory =
            File("resources")

        if (!resourcesDirectory.exists())
        {
            resourcesDirectory.mkdirs()
        }

        resourcesDirectory.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".resource.json") }
            .forEach {
                val resource = Json
                    .decodeFromStream<Resource>(
                        it.inputStream()
                    )

                logger.info(
                    "[Resources] Registered resource [${resource.id}, ${resource.version}]."
                )

                resources += resource
            }
    }
}
