package io.liftgate.server

import io.liftgate.server.models.Resource
import io.liftgate.server.models.server.ServerTemplate
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
lateinit var config: LiftgateServerConfig

object LoggerInternal
{
    fun info(message: String)
    {
        println(message)
    }
}

val logger = LoggerInternal

val resources = mutableListOf<Resource>()
val templates = mutableListOf<ServerTemplate>()

val pool: ScheduledExecutorService by lazy {
    Executors.newScheduledThreadPool(config.poolThreads)
}

fun listening(
    serverHost: String, serverPort: Int, timeoutMs: Int
): Boolean
{
    runCatching {
        Socket().use {
            it.connect(
                InetSocketAddress(serverHost, serverPort),
                timeoutMs
            )
        }
    }.onFailure {
        return false
    }.onSuccess {
        return true
    }

    return false
}
