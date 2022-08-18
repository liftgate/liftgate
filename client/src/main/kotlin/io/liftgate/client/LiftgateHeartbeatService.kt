package io.liftgate.client

import io.grpc.StatusRuntimeException
import io.liftgate.protocol.Authentication
import io.liftgate.protocol.AuthenticationStatus
import io.liftgate.protocol.ServerHeartbeat
import io.liftgate.protocol.ServerHeartbeatStatus
import io.liftgate.protocol.ServerRegistration
import io.liftgate.protocol.ServerRegistrationResponse
import io.liftgate.protocol.ServerRegistrationStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
class LiftgateHeartbeatService(
    private val client: LiftgateClient<*>
) : Runnable
{
    private val authentication = Authentication
        .newBuilder()
        .setToken(this.client.config.authToken)
        .build()

    enum class RegistrationResult
    {
        AuthenticationFailure, RegistrationDuplicate, Success
    }

    fun configure(): CompletableFuture<Void>
    {
        return this.registerAndMap().thenAccept {
            when (it)
            {
                RegistrationResult.AuthenticationFailure ->
                {
                    this.client.logger.info("Failed client <-> server authentication! Are you sure you're using the correct token?")
                    return@thenAccept
                }

                RegistrationResult.RegistrationDuplicate ->
                {
                    this.client.logger.info("A server with this server ID is already registered! Are you sure you're using a unique ID?")
                    return@thenAccept
                }

                RegistrationResult.Success ->
                {
                    this.client.logger.info("Scheduling heartbeat service at a delay of 2 seconds.")

                    Executors.newSingleThreadScheduledExecutor()
                        .scheduleAtFixedRate(
                            this, 0L, 2L, TimeUnit.SECONDS
                        )
                }

                else ->
                {
                    return@thenAccept
                }
            }
        }
    }

    fun registerAndMap(): CompletableFuture<RegistrationResult>
    {
        return this.register()
            .exceptionally {
                client.logger.log(Level.SEVERE, "Failed to complete registration process", it)
                return@exceptionally null
            }
            .thenApply {
                if (it.authentication == AuthenticationStatus.FAILURE)
                {
                    return@thenApply RegistrationResult.AuthenticationFailure
                }

                if (it.status == ServerRegistrationStatus.DUPLICATE_UID)
                {
                    return@thenApply RegistrationResult.RegistrationDuplicate
                }

                return@thenApply RegistrationResult.Success
            }
    }

    private fun register(): CompletableFuture<ServerRegistrationResponse>
    {
        val clientConfig = this.client.config

        val registration = ServerRegistration
            .newBuilder()
            .setAuthentication(
                Authentication.newBuilder()
                    .setToken(clientConfig.authToken)
                    .build()
            )
            .setDatacenter(clientConfig.registrationInfo.datacenter)
            .setServerId(clientConfig.registrationInfo.serverId)
            .setPort(clientConfig.registrationInfo.port)
            .addAllClassifiers(clientConfig.registrationInfo.groups)
            .putAllMetadata(this.client.metadata())
            .setTimestamp(System.currentTimeMillis())
            .build()

        return this.client.register(registration)
    }

    override fun run()
    {
        val request = ServerHeartbeat.newBuilder()
            .setAuthentication(this.authentication)
            .setServerId(this.client.config.registrationInfo.serverId)
            .putAllMetadata(this.client.metadata())
            .setTimestamp(System.currentTimeMillis())
            .build()

        if (this.client.closed)
        {
            return
        }

        val heartbeat = kotlin
            .runCatching {
                this.client.heartbeat(request).join()
            }
            .onFailure {
                if (it is StatusRuntimeException)
                {
                    this.client.logger.severe("It seems like the gRPC server is offline. (${it.status})")
                    return
                }

                this.client.logger.log(Level.SEVERE, "An exception was thrown during the heartbeat process", it)
            }
            .getOrNull()
            ?: return run {
                client.logger.info("Failed to continue heartbeat process, is there an error above?")
            }

        if (heartbeat.authentication == AuthenticationStatus.FAILURE)
        {
            this.client.logger.info("Failed client <-> server authentication! Are you sure you're using the correct token?")
            return
        }

        if (heartbeat.status == ServerHeartbeatStatus.UNREGISTERED_SERVER)
        {
            this.client.logger.info("Passed server heartbeat to unregistered server! Attempting to re-register...")
            this.register().join()
        }
    }
}
