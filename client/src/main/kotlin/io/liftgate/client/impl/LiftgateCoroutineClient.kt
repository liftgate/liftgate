package io.liftgate.client.impl

import io.liftgate.client.LiftgateClient
import io.liftgate.client.LiftgateClientConfig
import io.liftgate.protocol.AllServersResponse
import io.liftgate.protocol.Authentication
import io.liftgate.protocol.NetworkGrpcKt
import io.liftgate.protocol.ServerHeartbeat
import io.liftgate.protocol.ServerHeartbeatResponse
import io.liftgate.protocol.ServerRegistration
import io.liftgate.protocol.ServerRegistrationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
class LiftgateCoroutineClient(
    config: LiftgateClientConfig,
    logger: Logger,
    metadata: () -> Map<String, String>,
    private val coroutineScope: CoroutineScope
) : LiftgateClient<NetworkGrpcKt.NetworkCoroutineStub>(config, logger, metadata)
{
    private val stub by lazy {
        NetworkGrpcKt.NetworkCoroutineStub(this.channel)
    }

    override fun initialize()
    {
        this.stub
    }

    override fun register(
        registration: ServerRegistration
    ): CompletableFuture<ServerRegistrationResponse>
    {
        return this.coroutineScope.future {
            stub.register(registration)
        }
    }

    override fun heartbeat(
        registration: ServerHeartbeat
    ): CompletableFuture<ServerHeartbeatResponse>
    {
        return this.coroutineScope.future {
            stub.heartbeat(registration)
        }
    }

    override fun allServers(
        authentication: Authentication
    ): CompletableFuture<AllServersResponse>
    {
        return this.coroutineScope.future {
            stub.allServers(authentication)
        }
    }
}
