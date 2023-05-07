package io.liftgate.server.autoscale.availability.impl

import io.liftgate.server.autoscale.AutoScaleResult
import io.liftgate.server.autoscale.availability.AutoScaleAvailabilityStrategy
import io.liftgate.server.logger
import io.liftgate.server.models.server.registration.RegisteredServer
import io.liftgate.server.provision.ProvisionedServers

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
class PercentageAvailabilityStrategy : AutoScaleAvailabilityStrategy
{
    private val desiredCapacityRatio = 50.0F

    /**
     * Player counts often fluctuate regularly. We have a
     * 10% threshold to prevent provision/deprovision spams
     * when the desired ratio isn't EXACTLY [desiredCapacityRatio].
     */
    private val capacityThreshold = 10.0F

    override fun scale(
        servers: List<RegisteredServer>
    ): Pair<AutoScaleResult, Int>
    {
        val provisioned = servers
            .associateBy {  server ->
                ProvisionedServers.servers
                    .firstOrNull {
                        server.serverId == it.id
                    }
            }
            .filterNot { it.key != null }
            .mapKeys { it.key!! }

        val onlinePlayers = provisioned.values
            .sumOf {
                it.metadata["players"]?.toInt() ?: 0
            }

        val maxPlayersMappings = provisioned.values
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

        if (percentageFull > desiredCapacityRatio + capacityThreshold)
        {
            var requiredRatio: Float
            var serversDesired = 0

            do
            {
                serversDesired += 1
                requiredRatio = (onlinePlayers / (maxPlayers + (maxPlayersAvg * serversDesired))) * 100.0F
            } while (
                requiredRatio > desiredCapacityRatio + capacityThreshold
            )

            logger.info("scaling up to go below threshold maximum ($percentageFull -> $requiredRatio, $serversDesired)")

            return Pair(
                AutoScaleResult.ScaleUp, serversDesired
            )
        }

        if (percentageFull < desiredCapacityRatio - capacityThreshold)
        {
            var requiredRatio: Float
            var desiredDeProvisions = 0

            do
            {
                desiredDeProvisions += 1
                requiredRatio = (onlinePlayers / (maxPlayers - (maxPlayersAvg * desiredDeProvisions))) * 100.0F
            } while (
                requiredRatio > desiredCapacityRatio + capacityThreshold
            )

            if (requiredRatio < desiredCapacityRatio - capacityThreshold)
            {
                logger.info("maintaining as removing server will go below threshold minimum of ${desiredCapacityRatio - capacityThreshold}% when current is $percentageFull% and the desired ratio is $requiredRatio%")
                return Pair(AutoScaleResult.Maintain, 0)
            }

            logger.info("scaling down to go below threshold maximum ($percentageFull, $desiredDeProvisions)")

            return Pair(
                AutoScaleResult.ScaleDown, desiredDeProvisions
            )
        }

        logger.info("maintaining as no configuration to scale up/down was found")

        return Pair(AutoScaleResult.Maintain, 0)
    }
}
