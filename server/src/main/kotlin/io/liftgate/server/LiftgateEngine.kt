package io.liftgate.server

import io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import io.liftgate.server.network.NetworkRpcService
import io.liftgate.server.provision.ProvisionedServers
import java.net.InetSocketAddress

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
class LiftgateEngine
{
    val server = NettyServerBuilder
        .forAddress(
            InetSocketAddress(
                config.hostname,
                config.port
            )
        )
        .maxInboundMessageSize(26214400)
        .addService(NetworkRpcService)
        .build()!!

    fun start()
    {
        this.server.start()

        Runtime.getRuntime()
            .addShutdownHook(Thread {
                this.server.shutdownNow()

                ProvisionedServers.servers
                    .forEach {
                        it.kill()
                    }
            })
    }
}
