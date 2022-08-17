package io.liftgate.server.provision.step.impl

import io.liftgate.server.models.server.ServerTemplate
import io.liftgate.server.provision.step.ServerProvisionStep
import java.io.File
import java.util.*

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ExecutionStep : ServerProvisionStep
{
    override fun runStep(
        template: ServerTemplate, uid: String?, port: Int?,
        temporaryMeta: MutableMap<String, String>
    )
    {
        val containerUid = temporaryMeta["uid"] ?: uid

        val containerDirectory =
            File(temporaryMeta["directory"]!!)

        val replacedArguments = template
            .executions.startArguments
            .map {
                template.handleReplacements(it)
            }

        val command = "${template.executions.startCommand} ${replacedArguments.joinToString(" ")}"
        val tempFile = "startup-${UUID.randomUUID()}.sh"

        val startup = File(containerDirectory, tempFile)
            .apply {
                createNewFile()
                writeBytes(
                    """
                        #!/bin/bash
                        cd ${containerDirectory.absolutePath}
                        screen -dmS $containerUid bash -l -c '$command; exec bash'
                    """.trimIndent().encodeToByteArray()
                )
            }

        Runtime.getRuntime()
            .exec("chmod -R 777 ${startup.absolutePath}")
            .waitFor()

        Runtime.getRuntime()
            .exec("sh ${startup.absolutePath}")
            .waitFor()
    }
}
