package dev.liftgate.config

import dev.liftgate.minimalEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class ConfigTest {
    @Test
    fun `defaults resolve`() {
        val config = Config.fromEnv(minimalEnv)
        assertEquals(Role.RECONCILER, config.role)
        assertEquals("jdbc:postgresql://localhost:5432/liftgate", config.databaseUrl)
        assertEquals("liftgate", config.databaseUser)
        assertEquals(8080, config.httpPort)
        assertEquals("nats://localhost:4222", config.natsUrl)
        assertEquals("liftgate.app", config.deployDomain)
        assertEquals("liftgate-system", config.gatewayNamespace)
        assertEquals(32, config.secretsMasterKey.size)
        assertTrue(config.leaderElection)
        assertFalse(config.hazelcastKubernetes)
        assertNull(config.github)
        assertNull(config.runtimeClass)
    }

    @Test
    fun `missing master key names the variable`() {
        val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv - "LIFTGATE_SECRETS_MASTER_KEY") }
        assertTrue("LIFTGATE_SECRETS_MASTER_KEY" in error.message.orEmpty())
    }

    @Test
    fun `master key must decode to 32 bytes`() {
        val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_SECRETS_MASTER_KEY" to "short")) }
        assertTrue("LIFTGATE_SECRETS_MASTER_KEY" in error.message.orEmpty())
    }

    @Test
    fun `api role requires github credentials`() {
        val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_ROLE" to "api")) }
        assertTrue("LIFTGATE_GITHUB_APP_ID" in error.message.orEmpty())
    }

    @Test
    fun `github credentials, runtime class and leader election parse`() {
        val config = Config.fromEnv(
            minimalEnv + mapOf(
                "LIFTGATE_ROLE" to "all",
                "LIFTGATE_GITHUB_APP_ID" to "1",
                "LIFTGATE_GITHUB_APP_PRIVATE_KEY" to "pem",
                "LIFTGATE_GITHUB_WEBHOOK_SECRET" to "wh",
                "LIFTGATE_GITHUB_CLIENT_ID" to "cid",
                "LIFTGATE_GITHUB_CLIENT_SECRET" to "cs",
                "LIFTGATE_LEADER_ELECTION" to "off",
                "LIFTGATE_RUNTIME_CLASS" to "gvisor",
            ),
        )
        assertEquals(Role.ALL, config.role)
        assertEquals(GitHubConfig("1", "pem", "wh", "cid", "cs"), config.github)
        assertFalse(config.leaderElection)
        assertEquals("gvisor", config.runtimeClass)
    }
}
