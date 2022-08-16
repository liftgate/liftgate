package io.liftgate.server.server

import io.liftgate.server.LiftgateEngine
import io.liftgate.server.autoscale.AutoScaleServer
import io.liftgate.server.autoscale.AutoScaleServers
import io.liftgate.server.logger
import io.liftgate.server.models.server.ServerTemplate
import io.liftgate.server.provision.orderedProvisionSteps
import io.liftgate.server.resource.ResourceHandler
import io.liftgate.server.startup.StartupStep
import io.liftgate.server.templates
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import kotlin.system.measureTimeMillis

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ServerTemplateHandler : StartupStep
{
    fun findTemplateById(id: String) =
        templates.find {
            it.id == id
        }

    fun provision(
        template: ServerTemplate,
        uid: String? = null,
        port: Int? = null
    ): CompletableFuture<Void>
    {
        return CompletableFuture
            .runAsync {
                val metadata = mutableMapOf<String, String>()

                orderedProvisionSteps.forEach {
                    val milliseconds = measureTimeMillis {
                        kotlin.runCatching {
                            it.runStep(template, uid, port, metadata)
                        }.onFailure { throwable ->
                            logger.log(Level.SEVERE, "Failed provision step (${it.javaClass.name})", throwable)

                            metadata["directory"]?.apply {
                                val directory = File(this)
                                directory.deleteRecursively()
                            }
                            return@runAsync
                        }
                    }

                    logger.info("[Provision] Completed step in $milliseconds ms. (${it.javaClass.name})")
                }

                AutoScaleServers.servers.add(
                    AutoScaleServer(
                        metadata["uid"] ?: uid!!,
                        metadata["port"]?.toInt() ?: port!!
                    )
                )
            }
    }

    @OptIn(ExperimentalSerializationApi::class)
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
