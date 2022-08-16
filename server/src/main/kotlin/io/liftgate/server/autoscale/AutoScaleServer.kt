package io.liftgate.server.autoscale

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
data class AutoScaleServer(
    val id: String,
    val port: Int
)
{
    fun kill()
    {
        // TODO: actually use the stop command 
        Runtime.getRuntime()
            .exec("kill \$(lsof -t -i:$port)")
            .waitFor()
    }
}
