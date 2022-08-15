package io.liftgate.server.resource

import io.liftgate.server.LiftgateEngine
import io.liftgate.server.logger
import io.liftgate.server.models.Resource
import io.liftgate.server.startup.StartupStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ResourceHandler : StartupStep
{
    override fun perform(context: LiftgateEngine)
    {
        val resources =
            File("resources")

        if (!resources.exists())
        {
            resources.mkdirs()
        }

        resources.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".resource.json") }
            .forEach {
                val resource = Json
                    .decodeFromStream<Resource>(
                        it.inputStream()
                    )

                logger.info(
                    "[Resources] Registered resource [${resource.id}, ${resource.version}]."
                )
            }
    }
}
