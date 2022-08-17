package io.liftgate.client.coroutine

import io.liftgate.client.LiftgateClient
import io.liftgate.client.LiftgateClientConfig
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

    override fun register(
        registration: ServerRegistration
    ): CompletableFuture<ServerRegistrationResponse>
    {
        val completable =
            CompletableFuture<ServerRegistrationResponse>()

        stub.register(registration).apply {
            completable.complete(this)
        }

        return completable
    }

    override fun heartbeat(
        registration: ServerHeartbeat
    ): CompletableFuture<ServerHeartbeatResponse>
    {
        val completable =
            CompletableFuture<ServerHeartbeatResponse>()

        stub.heartbeat(registration).apply {
            completable.complete(this)
        }

        return completable
    }
}
