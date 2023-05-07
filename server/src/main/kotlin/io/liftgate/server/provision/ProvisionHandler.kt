package io.liftgate.server.provision

import io.liftgate.server.LiftgateEngine
import io.liftgate.server.logger
import io.liftgate.server.models.server.ServerTemplate
import io.liftgate.server.pool
import io.liftgate.server.provision.step.orderedProvisionSteps
import io.liftgate.server.server.ServerHandler
import io.liftgate.server.startup.StartupStep
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.system.measureTimeMillis

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
object ProvisionHandler : Runnable, StartupStep
{
    override fun perform(context: LiftgateEngine)
    {
        pool.scheduleAtFixedRate(this, 0L, 1L, TimeUnit.SECONDS)
    }

    override fun run()
    {
        val provisionUnverified = ProvisionedServers
            .servers.filter {
                !it.provisionVerified && System.currentTimeMillis() >= it.provisionLatestTimestamp +
                        Duration.ofSeconds(15L).toMillis()
            }

        for (provisioned in provisionUnverified)
        {
            val alive = ServerHandler
                .findServerByServerId(provisioned.id)

            if (alive == null)
            {
                provisioned.provisionChecks += 1

                if (provisioned.provisionChecks == 3)
                {
                    logger.info("[Provision] Server ${provisioned.id} failed to send heartbeat within 45 seconds of its startup, shutting down.")
                    ProvisionedServers.deProvision(provisioned)
                    continue
                }

                logger.info("[Provision] Server ${provisioned.id} failed check #${provisioned.provisionChecks}/#3.")
            } else
            {
                val millis = System.currentTimeMillis() - provisioned.provisionInitialTimestamp

                logger.info(
                    "[Provision] Received heartbeat from ${provisioned.id} within ${millis}ms (${millis / 1000L}s) of startup."
                )

                provisioned.provisionVerified = true
            }

            provisioned.provisionLatestTimestamp =
                System.currentTimeMillis()
        }
    }

    fun provision(
        template: ServerTemplate, uid: String? = null, port: Int? = null,
        defaultMeta: MutableMap<String, String> = mutableMapOf()
    ): String?
    {
        for (step in orderedProvisionSteps)
        {
            val milliseconds = measureTimeMillis {
                kotlin.runCatching {
                    step.runStep(template, uid, port, defaultMeta)
                }.onFailure { throwable ->
                    logger.log(Level.SEVERE, "Failed provision step (${step.javaClass.name})", throwable)

                    defaultMeta["directory"]?.apply {
                        val directory = File(this)
                        directory.deleteRecursively()
                    }
                    return null
                }
            }

            logger.info("[Provision] Completed step in $milliseconds ms. (${step.javaClass.name})")
        }

        ProvisionedServers.provision(
            ProvisionedServer(
                template.id, defaultMeta["uid"] ?: uid!!,
                defaultMeta["port"]?.toInt() ?: port!!,
                File(defaultMeta["directory"]!!)
            )
        )

        return defaultMeta["uid"]
    }
}
