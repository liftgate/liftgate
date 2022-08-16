package io.liftgate.server.provision.step.impl

import io.liftgate.server.models.server.ServerTemplate
import io.liftgate.server.provision.step.ServerProvisionStep
import java.io.File
import java.nio.file.Files

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ReplacementStep : ServerProvisionStep
{
    override fun runStep(
        template: ServerTemplate,
        uid: String?, port: Int?,
        temporaryMeta: MutableMap<String, String>
    )
    {
        val directory = File(
            temporaryMeta["directory"]!!
        )

        for (replacement in template.replacementFiles)
        {
            val replacementFile = File(
                directory, replacement
            )

            if (replacementFile.exists())
            {
                var text = replacementFile.readText()
                text = template.handleReplacements(text)

                temporaryMeta.forEach { (key, value) ->
                    text = text.replace("<$key>", value)
                }

                Files.write(
                    replacementFile.toPath(),
                    text.encodeToByteArray()
                )
            }
        }
    }
}
