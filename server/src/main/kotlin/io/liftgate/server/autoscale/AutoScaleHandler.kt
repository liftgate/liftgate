package io.liftgate.server.autoscale

import io.liftgate.server.LiftgateEngine
import io.liftgate.server.logger
import io.liftgate.server.models.server.ServerTemplate
import io.liftgate.server.pool
import io.liftgate.server.resource.ResourceHandler
import io.liftgate.server.startup.StartupStep
import io.liftgate.server.templates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
object AutoScaleHandler : StartupStep
{
    private val templates = mutableListOf<AutoScaleTemplate>()

    fun findAutoScaleTemplateByGroup(group: String) =
        this.templates.find { it.group == group }

    fun startAutoScaleService(template: AutoScaleTemplate)
    {
        val service = AutoScaleService(template)
        pool.scheduleAtFixedRate(
            service, 0L, 1L, TimeUnit.SECONDS
        )
    }

    override fun perform(context: LiftgateEngine)
    {
        val autoScaleDirectory =
            File("autoscale")

        if (!autoScaleDirectory.exists())
        {
            autoScaleDirectory.mkdirs()
        }

        autoScaleDirectory.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".autoscale.json") }
            .forEach {
                val template = Json
                    .decodeFromStream<AutoScaleTemplate>(
                        it.inputStream()
                    )

                logger.info(
                    "[Template] Registered autoscale template for ${template.group}."
                )

                templates += template
            }
    }
}
