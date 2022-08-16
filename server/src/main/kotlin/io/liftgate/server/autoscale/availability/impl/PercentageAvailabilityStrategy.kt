package io.liftgate.server.autoscale.availability.impl

import io.liftgate.server.autoscale.AutoScaleResult
import io.liftgate.server.autoscale.availability.AutoScaleAvailabilityStrategy
import io.liftgate.server.provision.ProvisionedServer

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
object PercentageAvailabilityStrategy : AutoScaleAvailabilityStrategy
{
    @JvmStatic
    val REQUIRED = 50.0F

    @JvmStatic
    val THRESHOLD = 5.0F

    override fun scale(
        servers: List<ProvisionedServer>
    ): Pair<AutoScaleResult, Int>
    {
        TODO("asdf")
    }
}
