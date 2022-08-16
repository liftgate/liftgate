package io.liftgate.server.provision.impl

import io.liftgate.server.models.server.ServerTemplate
import io.liftgate.server.provision.ServerProvisionStep
import java.io.File
import kotlin.concurrent.thread

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

        val arguments = arrayOf(
            "-dmS", containerUid, "bash", "-c",
            "'${
                template.executions.startCommand + " " + replacedArguments.joinToString(" ")
            }; exec bash'"
        )

        val process = ProcessBuilder("screen", *arguments)
            .directory(containerDirectory)
            .start()

        process.waitFor()

        println(
            process.inputStream.reader().readLines()
        )
    }
}
