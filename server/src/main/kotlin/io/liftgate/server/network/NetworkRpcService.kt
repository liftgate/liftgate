package io.liftgate.server.network

import io.liftgate.protocol.AllServersResponse
import io.liftgate.protocol.Authentication
import io.liftgate.protocol.AuthenticationStatus
import io.liftgate.protocol.NetworkGrpcKt
import io.liftgate.protocol.ServerHeartbeat
import io.liftgate.protocol.ServerHeartbeatResponse
import io.liftgate.protocol.ServerHeartbeatStatus
import io.liftgate.protocol.ServerRegistration
import io.liftgate.protocol.ServerRegistrationResponse
import io.liftgate.protocol.ServerRegistrationStatus
import io.liftgate.server.logger
import io.liftgate.server.server.ServerHandler
import io.liftgate.server.token.TokenGenerator

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object NetworkRpcService : NetworkGrpcKt.NetworkCoroutineImplBase()
{
    override suspend fun allServers(
        request: Authentication
    ): AllServersResponse
    {
        val response = AllServersResponse
            .newBuilder()

        if (request.token != TokenGenerator.cached)
        {
            return response
                .setAuthentication(AuthenticationStatus.FAILURE)
                .build()
        }

        return response
            .addAllServers(
                ServerHandler.findAllServers()
                    .map { it.registration }
            )
            .build()
    }

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

        return response
            .setStatus(ServerRegistrationStatus.SERVER_SUCCESS)
            .build()
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

        logger.info("Received heartbeat")

        return response
            .setStatus(ServerHeartbeatStatus.HEARTBEAT_SUCCESS)
            .build()
    }
}
