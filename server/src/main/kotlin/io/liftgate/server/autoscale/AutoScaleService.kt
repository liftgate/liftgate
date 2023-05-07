package io.liftgate.server.autoscale

import io.liftgate.server.autoscale.availability.AutoScaleAvailabilityStrategy
import io.liftgate.server.autoscale.provision.AutoScalePropertyChoiceScheme
import io.liftgate.server.logger
import io.liftgate.server.provision.ProvisionHandler
import io.liftgate.server.provision.ProvisionedServer
import io.liftgate.server.provision.ProvisionedServers
import io.liftgate.server.server.ServerHandler
import io.liftgate.server.server.ServerTemplateHandler
import java.util.logging.Level

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
class AutoScaleService(
    private val template: AutoScaleTemplate
) : Runnable
{
    private val provisioner = Class
        .forName(template.propertyChoiceScheme)
        .kotlin
        .let {
            it.objectInstance ?: it.java.newInstance()
        } as AutoScalePropertyChoiceScheme

    private val availability = Class
        .forName(template.availabilityStrategy)
        .kotlin
        .let {
            it.objectInstance ?: it.java.newInstance()
        } as AutoScaleAvailabilityStrategy

    data class ScaleJob(
        val result: AutoScaleResult,
        val monitored: List<String>
    )

    private var job: ScaleJob? = null

    override fun run()
    {
        runCatching(::caughtRun)
            .onFailure {
                logger.info("Could not autoscale")
                it.printStackTrace()
            }
    }

    private fun caughtRun()
    {
        if (job != null)
        {
            logger.info("${job!!.result.name} job in progress")
            val remaining = job!!.monitored
                .count {
                    val server = ServerHandler
                        .findServerByServerId(it)

                    if (job!!.result == AutoScaleResult.ScaleUp)
                        server == null else server != null
                }

            if (remaining <= 0)
            {
                logger.info("${job!!.result.name} job completed")
                job = null
            } else
            {
                logger.info(
                    "Job progress: ${
                        "%.2f".format(
                            ((job!!.monitored.size - remaining) / job!!.monitored.size) * 100.0f
                        )
                    }%"
                )
            }
            return
        }

        val servers = ServerHandler
            .findServersByClassifier(this.template.group)

        val provisioned = servers
            .mapNotNull { registered ->
                ProvisionedServers.servers.find { it.id == registered.serverId }
            }

        val strategy = availability.scale(
            servers,
            template.desiredMetricRatio,
            template.metricRatioThreshold
        )

        when (strategy.first)
        {
            AutoScaleResult.ScaleDown ->
            {
                val deProvisioned = provisioned
                    .takeLast(strategy.second)
                    .onEach {
                        ProvisionedServers.deProvision(it)
                    }

                job = ScaleJob(
                    result = AutoScaleResult.ScaleDown,
                    monitored = deProvisioned.map(ProvisionedServer::id)
                )
            }

            AutoScaleResult.ScaleUp ->
            {
                if (template.scaleUpMax == provisioned.size)
                {
                    logger.info("Hit scale-up provision limit of ${template.scaleUpMax}, skipping requested scale-up.")
                    return
                }

                val template = ServerTemplateHandler
                    .findTemplateById(this.template.template)
                    ?: return run {
                        logger.info("[AutoScale] Failed to find template by id: ${template.template}")
                    }

                logger.info("[AutoScale] Provisioning ${strategy.second} new servers for auto-scale")
                val monitoredReplicas = mutableListOf<String>()

                for (i in 1..strategy.second)
                {
                    val replicaUid = ProvisionHandler.provision(
                        template,
                        defaultMeta = mutableMapOf(
                            "propertyScheme" to this.provisioner.javaClass.name
                        )
                    )

                    replicaUid?.apply {
                        monitoredReplicas += this
                    }
                }

                job = ScaleJob(
                    result = AutoScaleResult.ScaleUp,
                    monitored = monitoredReplicas
                )
            }

            else ->
            {
            }
        }
    }
}
