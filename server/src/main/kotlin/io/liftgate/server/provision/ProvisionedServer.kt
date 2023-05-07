package io.liftgate.server.provision

import io.liftgate.server.execution.ExecutionUtilities
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
        // TODO: actually use the stop command
        ExecutionUtilities
            .runScript(
                "kill \$(lsof -t -i:$port)",
                directory
            )

        this.directory.deleteRecursively()
    }
}
