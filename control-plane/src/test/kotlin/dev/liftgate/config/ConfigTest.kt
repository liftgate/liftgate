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
        assertTrue(config.nodeSelector.isEmpty())
        assertFalse(config.registryInsecure)
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
    fun `node selector and insecure registry parse`() {
        val config = Config.fromEnv(minimalEnv + mapOf("LIFTGATE_NODE_SELECTOR" to "kubernetes.io/hostname=n1, node-role.kubernetes.io/worker=", "LIFTGATE_REGISTRY_INSECURE" to "true"))
        assertEquals(mapOf("kubernetes.io/hostname" to "n1", "node-role.kubernetes.io/worker" to ""), config.nodeSelector)
        assertTrue(config.registryInsecure)
    }

    @Test
    fun `malformed node selector names the variable`() {
        listOf("n1", "=n1", "a=b,").forEach {
            val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_NODE_SELECTOR" to it)) }
            assertTrue("LIFTGATE_NODE_SELECTOR" in error.message.orEmpty())
        }
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

    @Test
    fun `oauth providers exist only when their client is configured`() {
        val none = Config.fromEnv(minimalEnv)
        assertEquals(listOf(null, null, null), listOf(none.google, none.gitlab, none.bitbucket))
        assertEquals("https://gitlab.com", none.gitlabUrl)
        val config = Config.fromEnv(
            minimalEnv + mapOf(
                "LIFTGATE_GOOGLE_CLIENT_ID" to "g",
                "LIFTGATE_GOOGLE_CLIENT_SECRET" to "gs",
                "LIFTGATE_GITLAB_CLIENT_ID" to "l",
                "LIFTGATE_GITLAB_CLIENT_SECRET" to "ls",
                "LIFTGATE_GITLAB_URL" to "https://git.example/",
            ),
        )
        assertEquals(OAuthClient("g", "gs"), config.google)
        assertEquals(OAuthClient("l", "ls"), config.gitlab)
        assertEquals("https://git.example", config.gitlabUrl)
        assertNull(config.bitbucket)
        val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_BITBUCKET_CLIENT_ID" to "b")) }
        assertTrue("LIFTGATE_BITBUCKET_CLIENT_SECRET" in error.message.orEmpty())
    }
}
