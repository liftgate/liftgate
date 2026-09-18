package dev.liftgate

import dev.liftgate.config.Config
import java.util.Base64

val minimalEnv = mapOf(
    "LIFTGATE_ROLE" to "reconciler",
    "LIFTGATE_SECRETS_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32)),
)

fun testConfig(overrides: Map<String, String> = emptyMap()) = Config.fromEnv(minimalEnv + overrides)
