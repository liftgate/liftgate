package io.liftgate.client

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
data class LiftgateClientConfig @JvmOverloads constructor(
    val hostname: String = "0.0.0.0",
    val port: Int = 8360,
    val maxInboundMessageSize: Int = 26214400,
    val authToken: String = "example",
    val registrationInfo: RegistrationInfo = RegistrationInfo()
)

data class RegistrationInfo @JvmOverloads constructor(
    val serverId: String = "server-1",
    val port: Int = 25565,
    val groups: List<String> = listOf("server"),
    val datacenter: String = "dc-1"
)
