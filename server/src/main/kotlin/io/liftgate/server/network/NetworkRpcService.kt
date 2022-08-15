package io.liftgate.server.network

import io.liftgate.protocol.AuthenticationStatus
import io.liftgate.protocol.NetworkGrpcKt
import io.liftgate.protocol.ServerHeartbeat
import io.liftgate.protocol.ServerHeartbeatResponse
import io.liftgate.protocol.ServerHeartbeatStatus
import io.liftgate.protocol.ServerRegistration
import io.liftgate.protocol.ServerRegistrationResponse
import io.liftgate.protocol.ServerRegistrationStatus
import io.liftgate.server.server.ServerHandler
import io.liftgate.server.token.TokenGenerator

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object NetworkRpcService : NetworkGrpcKt.NetworkCoroutineImplBase()
{
    override suspend fun register(
        request: ServerRegistration
    ): ServerRegistrationResponse
    {
        val response = ServerRegistrationResponse
            .newBuilder()

        if (request.authentication.token != TokenGenerator.cached)
        {
            return response
                .setAuthentication(AuthenticationStatus.FAILURE)
                .build()
        }

        response.authentication = AuthenticationStatus.SUCCESS

        val existing = ServerHandler
            .findServerByServerId(request.serverId)

        if (existing != null)
        {
            return response
                .setStatus(ServerRegistrationStatus.DUPLICATE_UID)
                .build()
        }

        ServerHandler.register(request)

        return response.build()
    }

    override suspend fun heartbeat(
        request: ServerHeartbeat
    ): ServerHeartbeatResponse
    {
        val response = ServerHeartbeatResponse
            .newBuilder()

        if (request.authentication.token != TokenGenerator.cached)
        {
            return response
                .setAuthentication(AuthenticationStatus.FAILURE)
                .build()
        }

        response.authentication = AuthenticationStatus.SUCCESS

        val existing = ServerHandler
            .findServerByServerId(request.serverId)
            ?: return response
                .setStatus(ServerHeartbeatStatus.UNREGISTERED_SERVER)
                .build()

        existing.timestamp = System.currentTimeMillis()
        existing.metadata.putAll(request.metadataMap)

        return response.build()
    }
}
