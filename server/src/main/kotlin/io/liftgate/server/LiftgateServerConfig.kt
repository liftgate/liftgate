package io.liftgate.server

import kotlinx.serialization.Serializable

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
@Serializable
data class LiftgateServerConfig(
    val port: Int = 8360,
    val autoProvisionedServerDirectory: String = "/var/lib/liftgate/autoProvisioned"
)
