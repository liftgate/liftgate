package io.liftgate.client

import io.grpc.ManagedChannelBuilder
import io.grpc.stub.AbstractStub
import io.liftgate.protocol.AllServersResponse
import io.liftgate.protocol.Authentication
import io.liftgate.protocol.ServerHeartbeat
import io.liftgate.protocol.ServerHeartbeatResponse
import io.liftgate.protocol.ServerRegistration
import io.liftgate.protocol.ServerRegistrationResponse
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
abstract class LiftgateClient<T : AbstractStub<*>>(
    val config: LiftgateClientConfig,
    val logger: Logger,
    val metadata: () -> Map<String, String>
) : Closeable
{
    internal val channel by lazy {
        ManagedChannelBuilder
            .forAddress(this.config.hostname, this.config.port)
            .maxInboundMessageSize(this.config.maxInboundMessageSize)
            .usePlaintext()
            .build()
    }

    abstract fun initialize()

    abstract fun register(registration: ServerRegistration): CompletableFuture<ServerRegistrationResponse>
    abstract fun heartbeat(registration: ServerHeartbeat): CompletableFuture<ServerHeartbeatResponse>

    abstract fun allServers(authentication: Authentication): CompletableFuture<AllServersResponse>

    override fun close()
    {
        this.channel.shutdownNow()
    }
}
