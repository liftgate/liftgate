package io.liftgate.server.execution

import java.io.File
import java.util.*

/**
 * @author GrowlyX
 * @since 5/6/2023
 */
object ExecutionUtilities
{
    fun runScript(command: String, directory: File)
    {
        val tempFile = "script-${UUID.randomUUID()}.sh"

        val startup = File(directory, tempFile)
                .apply {
                    createNewFile()
                    writeBytes(
                            """
                        #!/bin/bash
                        cd ${directory.absolutePath}
                        $command
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
