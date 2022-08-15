package io.liftgate.server.server

import io.liftgate.server.LiftgateEngine
import io.liftgate.server.logger
import io.liftgate.server.models.server.ServerTemplate
import io.liftgate.server.resource.ResourceHandler
import io.liftgate.server.resources
import io.liftgate.server.startup.StartupStep
import io.liftgate.server.templates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ServerTemplateHandler : StartupStep
{
    override fun perform(context: LiftgateEngine)
    {
        val templatesDirectory =
            File("templates")

        if (!templatesDirectory.exists())
        {
            templatesDirectory.mkdirs()
        }

        templatesDirectory.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".template.json") }
            .forEach {
                val template = Json
                    .decodeFromStream<ServerTemplate>(
                        it.inputStream()
                    )

                for (dependency in template.dependencies)
                {
                    val existing = ResourceHandler
                        .findResourceByReference(dependency)

                    if (existing == null)
                    {
                        logger.info("[Template] Invalid template detected: dependency ${dependency.id} does not exist.")
                        return@forEach
                    }
                }

                logger.info(
                    "[Template] Registered template [${template.id}]."
                )

                templates += template
            }
    }
}
