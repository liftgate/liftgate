package io.liftgate.server.server

import io.liftgate.server.logger

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ServerHeartbeatMonitor : Runnable
{
    override fun run()
    {
        val servers = ServerHandler
            .findCriticalServers()

        for (server in servers)
        {
            ServerHandler.unregisterServer(server.serverId)
        }
    }
}
