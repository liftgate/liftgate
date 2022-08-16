package io.liftgate.server.autoscale.availability

import io.liftgate.server.autoscale.AutoScaleResult
import io.liftgate.server.provision.ProvisionedServer

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
interface AutoScaleAvailabilityStrategy
{
    fun scale(servers: List<ProvisionedServer>): Pair<AutoScaleResult, Int>
}
