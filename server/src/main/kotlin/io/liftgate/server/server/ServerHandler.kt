package io.liftgate.server.server

import io.liftgate.protocol.ServerHeartbeat
import io.liftgate.protocol.ServerRegistration

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ServerHandler
{
    private val servers =
        mutableMapOf<String, ServerRegistration>()

    fun findServerByHeartbeat(heartbeat: ServerHeartbeat) =
        this.servers.values.find {
            it.serverId == heartbeat.serverId
        }

    fun findServersByDatacenter(datacenter: String) =
        this.servers.values.filter {
            it.datacenter == it.datacenter
        }

    fun findServerByServerId(serverId: String) =
        this.servers.values.find {
            it.serverId == serverId
        }

    fun findServerByPort(port: Int) =
        this.servers.values.find {
            it.port == port
        }
}
