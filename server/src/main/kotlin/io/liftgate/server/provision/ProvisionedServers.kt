package io.liftgate.server.provision

import io.liftgate.server.logger

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ProvisionedServers
{
    val servers = mutableListOf<ProvisionedServer>()

    fun provision(server: ProvisionedServer)
    {
        this.servers.add(server)
    }

    fun deProvision(server: ProvisionedServer)
    {
        this.servers.remove(server)
        server.kill()

        logger.info("[Provision] Deprovisioning sevrer ${server.id}")
    }
}
