package io.liftgate.server.autoscale.availability.impl

import io.liftgate.server.autoscale.AutoScaleResult
import io.liftgate.server.autoscale.availability.AutoScaleAvailabilityStrategy
import io.liftgate.server.models.server.registration.RegisteredServer
import io.liftgate.server.provision.ProvisionedServer
import io.liftgate.server.server.ServerHandler

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
class PercentageAvailabilityStrategy : AutoScaleAvailabilityStrategy
{
    private val required = 50.0F
    private val threshold = 5.0F

    override fun scale(
        servers: List<RegisteredServer>
    ): Pair<AutoScaleResult, Int>
    {
        val onlinePlayers = servers
            .sumOf {
                it.metadata["players"]?.toInt() ?: 0
            }

        val maxPlayersMappings = servers
            .map {
                it.metadata["max-players"]?.toInt() ?: 0
            }

        val maxPlayers = maxPlayersMappings.sum()

        if (onlinePlayers <= 0 || maxPlayers <= 0)
        {
            return Pair(AutoScaleResult.ScaleUp, 1)
        }

        val maxPlayersAvg = maxPlayersMappings.average().toFloat()

        val ratio = (onlinePlayers / maxPlayers) * 100.0F

        if (
            // ensure ratio is within threshold to maintain system
            ratio <= required + threshold ||
            ratio >= required - threshold
        )
        {
            return Pair(AutoScaleResult.Maintain, 0)
        }

        if (ratio >= required + threshold)
        {
            var requiredRatio = -1.0F
            var serversToProvision = 0

            while (requiredRatio <= required)
            {
                serversToProvision += 1
                requiredRatio = (onlinePlayers / (maxPlayersAvg * serversToProvision)) * 100.0F
            }

            return Pair(
                AutoScaleResult.ScaleUp, serversToProvision
            )
        }

        if (ratio <= required - threshold)
        {
            var requiredRatio = -1.0F
            var serversToDeProvision = 0

            while (requiredRatio >= required)
            {
                serversToDeProvision += 1
                requiredRatio = (onlinePlayers / (maxPlayers - (maxPlayersAvg * serversToDeProvision))) * 100.0F
            }

            if (requiredRatio <= required - threshold)
            {
                return Pair(AutoScaleResult.Maintain, 0)
            }

            return Pair(
                AutoScaleResult.ScaleDown, serversToDeProvision
            )
        }

        return Pair(AutoScaleResult.Maintain, 0)
    }
}
