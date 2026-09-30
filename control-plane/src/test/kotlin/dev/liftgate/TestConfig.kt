package dev.liftgate

import dev.liftgate.cache.Cache
import dev.liftgate.config.Config
import dev.liftgate.db.Db
import dev.liftgate.db.Outbox
import dev.liftgate.events.Subject
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.Base64

val minimalEnv = mapOf(
    "LIFTGATE_ROLE" to "reconciler",
    "LIFTGATE_SECRETS_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32)),
    "LIFTGATE_RUNTIME_CLASS" to "gvisor",
)

fun testConfig(overrides: Map<String, String> = emptyMap()) = Config.fromEnv(minimalEnv + overrides)

val unlimitedCache = mockk<Cache> { every { allow(any(), any(), any()) } returns true }

val discardingDb = mockk<Db> { coEvery { tx<Any?>(any()) } returns null }

suspend fun Db.teardowns() = tx { Outbox.selectAll().where { Outbox.subject eq Subject.TEARDOWN_REQUESTED.value }.map { it[Outbox.payload].getValue("namespace").jsonPrimitive.content } }

fun JdbcTransaction.waitingLocks() = exec("select count(*) from pg_locks where not granted") { it.next(); it.getLong(1) }
