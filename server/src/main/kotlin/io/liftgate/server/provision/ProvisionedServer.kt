package io.liftgate.server.provision

import io.liftgate.server.execution.ExecutionUtilities
import io.liftgate.server.server.ServerTemplateHandler
import java.io.File

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
data class ProvisionedServer(
    val template: String, val id: String, val port: Int, val directory: File
)
{
    var provisionInitialTimestamp = System.currentTimeMillis()
    var provisionLatestTimestamp = System.currentTimeMillis()

    var provisionChecks = 0
    var provisionVerified = false

    fun kill()
    {
        val shutdown = ServerTemplateHandler
            .findTemplateById(template)
            ?.executions?.shutdown

        ExecutionUtilities
            .runScript(
                """
                    ${
                        if (shutdown != null)
                        {
                            "screen -X -S $id $shutdown"
                        } else
                        {
                            "kill \$(lsof -t -i:$port)"
                        }
                    }
                    rm -rf ${this.directory.absolutePath}
                    screen -X -S $id exit
                """.trimIndent(),
                directory
            )
    }
}
