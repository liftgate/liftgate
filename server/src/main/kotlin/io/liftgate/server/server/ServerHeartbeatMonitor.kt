package io.liftgate.server.server

import java.time.Duration

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ServerHeartbeatMonitor : Runnable
{
    override fun run()
    {
        val servers = ServerHandler
            .findAllServers()
            .filter {
                it.timestamp + Duration.ofSeconds(10L).toMillis() <= System.currentTimeMillis()
            }

        for (server in servers)
        {
            ServerHandler.unregisterServer(server.serverId)
        }
    }
}
