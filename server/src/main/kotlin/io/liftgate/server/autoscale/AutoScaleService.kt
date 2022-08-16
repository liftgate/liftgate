package io.liftgate.server.autoscale

import io.liftgate.server.autoscale.availability.AutoScaleAvailabilityStrategy
import io.liftgate.server.autoscale.provision.AutoScalePropertyChoiceScheme
import io.liftgate.server.logger
import io.liftgate.server.provision.ProvisionHandler
import io.liftgate.server.provision.ProvisionedServers
import io.liftgate.server.server.ServerHandler
import io.liftgate.server.server.ServerTemplateHandler

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

    override fun run()
    {
        val servers = ServerHandler
            .findServersByClassifier(this.template.group)

        val provisioned = servers
            .mapNotNull { registered ->
                ProvisionedServers.servers.find { it.id == registered.serverId }
            }

        val strategy = availability.scale(servers)

        when (strategy.first)
        {
            AutoScaleResult.ScaleDown ->
            {
                provisioned.takeLast(strategy.second)
                    .forEach {
                        ProvisionedServers.deProvision(it)
                    }
            }

            AutoScaleResult.ScaleUp ->
            {
                val template = ServerTemplateHandler
                    .findTemplateById(this.template.template)
                    ?: return run {
                        logger.info("[AutoScale] Failed to find template by id: ${template.template}")
                    }

                for (i in 1..strategy.second)
                {
                    logger.info("[AutoScale] Provisioning ${strategy.second} new servers for auto-scale")

                    ProvisionHandler.provision(
                        template,
                        defaultMeta = mutableMapOf(
                            "propertyScheme" to this.provisioner.javaClass.name
                        )
                    )
                }
            }

            else ->
            {
            }
        }
    }
}
