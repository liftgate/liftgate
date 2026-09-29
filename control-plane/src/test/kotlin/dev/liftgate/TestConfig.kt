package dev.liftgate

import dev.liftgate.cache.Cache
import dev.liftgate.config.Config
import dev.liftgate.db.Db
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.Base64

val minimalEnv = mapOf(
    "LIFTGATE_ROLE" to "reconciler",
    "LIFTGATE_SECRETS_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32)),
    "LIFTGATE_RUNTIME_CLASS" to "gvisor",
)

fun testConfig(overrides: Map<String, String> = emptyMap()) = Config.fromEnv(minimalEnv + overrides)

val unlimitedCache = mockk<Cache> { every { allow(any(), any(), any()) } returns true }

val discardingDb = mockk<Db> { coEvery { tx<Any?>(any()) } returns null }
