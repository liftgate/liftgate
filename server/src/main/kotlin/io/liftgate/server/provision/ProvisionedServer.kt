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
        val matching = File("/run/screen/S-root")
            .listFiles { _, name ->
                name.contains(id)
            }

        val match = matching?.firstOrNull()?.absolutePath ?: return

        ExecutionUtilities
            .runScript(
                """
                    kill $(lsof -t -i:$port)
                    rm -rf ${this.directory.absolutePath}
                    rm $match
                """.trimIndent(),
                directory
            )
    }
}
