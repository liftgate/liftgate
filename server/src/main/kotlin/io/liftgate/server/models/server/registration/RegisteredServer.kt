package io.liftgate.server.models.server.registration

import io.liftgate.protocol.ServerRegistration

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
data class RegisteredServer(
    val serverId: String,
    val datacenter: String,
    val port: Int,
    val metadata: MutableMap<String, String>,
    val classifiers: List<String>,
    var timestamp: Long = System.currentTimeMillis(),
    val registration: ServerRegistration
)
