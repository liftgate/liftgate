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
        val directory = temporaryMeta["directory"]!!

        for (replacement in template.replacementFiles)
        {
            val replacementFile = File(
                "$directory${File.separator}${
                    replacement.replace("/", File.separator)
                }"
            )

            if (replacementFile.exists())
            {
                var text = replacementFile.readText()
                text = template.handleReplacements(text)

                text = text.replace(
                    "<server-id>",
                        temporaryMeta["uid"] ?: uid!!
                )

                text = text.replace(
                        "<server-port>",
                        (temporaryMeta["port"] ?: port!!).toString()
                )

                text = text.replace(
                    "<server-group>", template.id
                )

                Files.write(
                    replacementFile.toPath(),
                    text.encodeToByteArray()
                )
            }
        }
    }
}
