package io.liftgate.server.provision

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
data class ProvisionedServer(
    val template: String, val id: String, val port: Int
)
{
    var provisionInitialTimestamp = System.currentTimeMillis()
    var provisionLatestTimestamp = System.currentTimeMillis()

    var provisionChecks = 0
    var provisionVerified = false

    fun kill()
    {
        // TODO: actually use the stop command
        Runtime.getRuntime()
            .exec("kill \$(lsof -t -i:$port)")
            .waitFor()
    }
}
