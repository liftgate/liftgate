package io.liftgate.server.network

import io.liftgate.protocol.NetworkGrpcKt
import io.liftgate.protocol.ServerHeartbeat
import io.liftgate.protocol.ServerHeartbeatResponse
import io.liftgate.protocol.ServerRegistration
import io.liftgate.protocol.ServerRegistrationResponse

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object NetworkRpcService : NetworkGrpcKt.NetworkCoroutineImplBase()
{
    override suspend fun register(request: ServerRegistration): ServerRegistrationResponse
    {
        return super.register(request)
    }

    override suspend fun heartbeat(request: ServerHeartbeat): ServerHeartbeatResponse
    {
        return super.heartbeat(request)
    }
}
