package io.liftgate.client.coroutine

import io.liftgate.client.LiftgateClient
import io.liftgate.client.LiftgateClientConfig
import io.liftgate.protocol.NetworkGrpcKt
import io.liftgate.protocol.ServerHeartbeat
import io.liftgate.protocol.ServerHeartbeatResponse
import io.liftgate.protocol.ServerRegistration
import io.liftgate.protocol.ServerRegistrationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
        val completable =
            CompletableFuture<ServerRegistrationResponse>()

        this.coroutineScope.launch {
            kotlin.runCatching {
                stub.register(registration)
            }.onFailure {
                it.printStackTrace()
            }.apply {
                completable.complete(this.getOrNull())
            }
        }

        return completable
    }

    override fun heartbeat(
        registration: ServerHeartbeat
    ): CompletableFuture<ServerHeartbeatResponse>
    {
        val completable =
            CompletableFuture<ServerHeartbeatResponse>()

        this.coroutineScope.launch {
            kotlin.runCatching {
                stub.heartbeat(registration)
            }.onFailure {
                it.printStackTrace()
            }.apply {
                completable.complete(this.getOrNull())
            }
        }

        return completable
    }
}
