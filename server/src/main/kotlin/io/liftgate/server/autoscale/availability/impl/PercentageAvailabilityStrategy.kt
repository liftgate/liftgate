package io.liftgate.server.autoscale.availability.impl

import io.liftgate.server.autoscale.AutoScaleResult
import io.liftgate.server.autoscale.availability.AutoScaleAvailabilityStrategy
import io.liftgate.server.logger
import io.liftgate.server.models.server.registration.RegisteredServer

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
class PercentageAvailabilityStrategy : AutoScaleAvailabilityStrategy
{
    private val desiredCapacityRatio = 50.0F
    private val capacityThreshold = 10.0F

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
            logger.info("maintaining due to 0 players/0 max players")
            return Pair(AutoScaleResult.Maintain, 0)
        }

        val maxPlayersAvg = maxPlayersMappings.average().toFloat()
        val percentageFull = (onlinePlayers / maxPlayers) * 100.0F

        if (
            // ensure ratio is within threshold to maintain system
            percentageFull <= desiredCapacityRatio + capacityThreshold &&
            percentageFull >= desiredCapacityRatio - capacityThreshold
        )
        {
            logger.info("maintaining due to desired ratio within threshold ($percentageFull)")
            return Pair(AutoScaleResult.Maintain, 0)
        }

        if (percentageFull <= desiredCapacityRatio - capacityThreshold)
        {
            var requiredRatio = -1.0F
            var serversDesired = 0

            while (requiredRatio < desiredCapacityRatio - capacityThreshold)
            {
                serversDesired += 1
                requiredRatio = (onlinePlayers / (maxPlayers + (maxPlayersAvg * serversDesired))) * 100.0F
            }

            logger.info("scaling up to go above threshold minimum ($percentageFull, $serversDesired)")

            return Pair(
                AutoScaleResult.ScaleUp, serversDesired
            )
        }

        if (percentageFull >= desiredCapacityRatio + capacityThreshold)
        {
            var requiredRatio = -1.0F
            var desiredDeprovisions = 0

            while (requiredRatio > desiredCapacityRatio + capacityThreshold)
            {
                desiredDeprovisions += 1
                requiredRatio = (onlinePlayers / (maxPlayers - (maxPlayersAvg * desiredDeprovisions))) * 100.0F
            }

            if (requiredRatio < desiredCapacityRatio - capacityThreshold)
            {
                logger.info("maintaining as removing server will go below threshold minimu8m")
                return Pair(AutoScaleResult.Maintain, 0)
            }

            logger.info("scaling down to go below threshold maximum ($percentageFull, $desiredDeprovisions)")

            return Pair(
                AutoScaleResult.ScaleDown, desiredDeprovisions
            )
        }

        logger.info("maintaining as no configuration to scale up/down was found")

        return Pair(AutoScaleResult.Maintain, 0)
    }
}
