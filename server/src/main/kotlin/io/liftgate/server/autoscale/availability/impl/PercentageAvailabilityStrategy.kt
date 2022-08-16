package io.liftgate.server.autoscale.availability.impl

import io.liftgate.server.autoscale.AutoScaleResult
import io.liftgate.server.autoscale.availability.AutoScaleAvailabilityStrategy
import io.liftgate.server.provision.ProvisionedServer
import io.liftgate.server.server.ServerHandler

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
        val mappings = servers
            .mapNotNull {
                ServerHandler.findServerByServerId(it.id)
            }

        val onlinePlayers = mappings
            .sumOf {
                it.metadata["players"]?.toInt() ?: 0
            }

        val maxPlayersMappings = mappings
            .map {
                it.metadata["max-players"]?.toInt() ?: 0
            }

        val maxPlayers = maxPlayersMappings.sum()
        val maxPlayersAvg = maxPlayersMappings.average().toFloat()

        val ratio = (onlinePlayers / maxPlayers) * 100.0F

        if (
            // ensure ratio is within threshold to maintain system
            ratio <= REQUIRED + THRESHOLD ||
            ratio >= REQUIRED - THRESHOLD
        )
        {
            return Pair(AutoScaleResult.MAINTAIN, 0)
        }

        if (ratio >= REQUIRED + THRESHOLD)
        {
            var requiredRatio = -1.0F
            var serversToProvision = 0

            while (requiredRatio <= REQUIRED)
            {
                serversToProvision += 1
                requiredRatio = (onlinePlayers / (maxPlayersAvg * serversToProvision)) * 100.0F
            }

            return Pair(
                AutoScaleResult.SCALE_UP, serversToProvision
            )
        }

        if (ratio <= REQUIRED - THRESHOLD)
        {
            var requiredRatio = -1.0F
            var serversToDeProvision = 0

            while (requiredRatio >= REQUIRED)
            {
                serversToDeProvision += 1
                requiredRatio = (onlinePlayers / (maxPlayers - (maxPlayersAvg * serversToDeProvision))) * 100.0F
            }

            return Pair(
                AutoScaleResult.SCALE_DOWN, serversToDeProvision
            )
        }

        return Pair(AutoScaleResult.MAINTAIN, 0)
    }
}
