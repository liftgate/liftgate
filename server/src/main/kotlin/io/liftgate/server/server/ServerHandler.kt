package io.liftgate.server.server

import io.liftgate.protocol.ServerHeartbeat
import io.liftgate.protocol.ServerRegistration
import io.liftgate.server.LiftgateEngine
import io.liftgate.server.logger
import io.liftgate.server.models.server.registration.RegisteredServer
import io.liftgate.server.pool
import io.liftgate.server.startup.StartupStep
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ServerHandler : StartupStep
{
    private val servers =
        mutableMapOf<String, RegisteredServer>()

    fun unregisterServer(serverId: String)
    {
        this.servers.remove(serverId)

        logger.info("[Server] Unregistered critical server with ID: $serverId")
    }

    fun register(registration: ServerRegistration)
    {
        this.servers[registration.serverId] =
            RegisteredServer(
                registration.serverId,
                registration.datacenter,
                registration.port,
                registration.metadataMap,
                registration.classifiersList.toMutableList()
            )

        logger.info("[Server] Received server registration with ID: ${registration.serverId}")
    }

    private fun findAllServers() =
        this.servers.values.toList()

    fun findCriticalServers() =
        this.findAllServers()
            .filter {
                System.currentTimeMillis() <= it.timestamp + Duration
                    .ofSeconds(5L).toMillis()
            }

    fun findServerByHeartbeat(heartbeat: ServerHeartbeat) =
        this.servers.values.find {
            it.serverId == heartbeat.serverId
        }

    fun findServersByClassifier(classifier: String) =
        this.servers.values.filter {
            classifier in it.classifiers
        }

    fun findServersByDatacenter(datacenter: String) =
        this.servers.values.filter {
            it.datacenter == datacenter
        }

    fun findServerByServerId(serverId: String) =
        this.servers.values.find {
            it.serverId == serverId
        }

    fun findServerByPort(port: Int) =
        this.servers.values.find {
            it.port == port
        }

    override fun perform(context: LiftgateEngine)
    {
        pool.scheduleAtFixedRate(
            ServerHeartbeatMonitor, 0L, 1L, TimeUnit.SECONDS
        )

        logger.info("[Monitor] Started server heartbeat monitor.")
    }
}
