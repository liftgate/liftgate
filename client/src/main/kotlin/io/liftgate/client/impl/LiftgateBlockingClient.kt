package io.liftgate.client.impl

import io.liftgate.client.LiftgateClient
import io.liftgate.client.LiftgateClientConfig
import io.liftgate.protocol.AllServersResponse
import io.liftgate.protocol.Authentication
import io.liftgate.protocol.NetworkGrpc
import io.liftgate.protocol.NetworkGrpc.NetworkFutureStub
import io.liftgate.protocol.ServerHeartbeat
import io.liftgate.protocol.ServerHeartbeatResponse
import io.liftgate.protocol.ServerRegistration
import io.liftgate.protocol.ServerRegistrationResponse
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
class LiftgateBlockingClient(
    config: LiftgateClientConfig,
    logger: Logger,
    metadata: () -> Map<String, String>
) : LiftgateClient<NetworkFutureStub>(config, logger, metadata)
{
    private val stub by lazy {
        NetworkGrpc.newBlockingStub(this.channel)
    }

    override fun initialize()
    {
        this.stub
    }

    override fun allServers(
        authentication: Authentication
    ): CompletableFuture<AllServersResponse>
    {
        return CompletableFuture
            .completedFuture(
                stub.allServers(authentication)
            )
    }

    override fun register(
        registration: ServerRegistration
    ): CompletableFuture<ServerRegistrationResponse>
    {
        return CompletableFuture
            .completedFuture(
                stub.register(registration)
            )
    }

    override fun heartbeat(
        registration: ServerHeartbeat
    ): CompletableFuture<ServerHeartbeatResponse>
    {
        return CompletableFuture
            .completedFuture(
                stub.heartbeat(registration)
            )
    }
}
