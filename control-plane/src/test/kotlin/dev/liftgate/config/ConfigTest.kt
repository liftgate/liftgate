package dev.liftgate.config

import dev.liftgate.minimalEnv
import io.fabric8.kubernetes.api.model.TolerationBuilder
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
    private val github = mapOf(
        "LIFTGATE_GITHUB_APP_ID" to "1",
        "LIFTGATE_GITHUB_APP_PRIVATE_KEY" to "pem",
        "LIFTGATE_GITHUB_WEBHOOK_SECRET" to "wh",
        "LIFTGATE_GITHUB_CLIENT_ID" to "cid",
        "LIFTGATE_GITHUB_CLIENT_SECRET" to "cs",
    )

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
        assertEquals("gvisor", config.runtimeClass)
        assertTrue(config.nodeSelector.isEmpty())
        assertTrue(config.workloadTolerations.isEmpty() && config.buildTolerations.isEmpty())
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
    fun `tenant and build pods take their own node selector and fall back to the platform one`() {
        val platform = minimalEnv + ("LIFTGATE_NODE_SELECTOR" to "liftgate.dev/pool=platform")
        val shared = Config.fromEnv(platform)
        assertEquals(mapOf("liftgate.dev/pool" to "platform"), shared.workloadNodeSelector)
        assertEquals(mapOf("liftgate.dev/pool" to "platform"), shared.buildNodeSelector)
        val pools = Config.fromEnv(platform + mapOf("LIFTGATE_WORKLOAD_NODE_SELECTOR" to "liftgate.dev/pool=workloads", "LIFTGATE_BUILD_NODE_SELECTOR" to "liftgate.dev/pool=build"))
        assertEquals(mapOf("liftgate.dev/pool" to "platform"), pools.nodeSelector)
        assertEquals(mapOf("liftgate.dev/pool" to "workloads"), pools.workloadNodeSelector)
        assertEquals(mapOf("liftgate.dev/pool" to "build"), pools.buildNodeSelector)
    }

    @Test
    fun `malformed node selector names the variable`() {
        listOf("LIFTGATE_NODE_SELECTOR", "LIFTGATE_WORKLOAD_NODE_SELECTOR", "LIFTGATE_BUILD_NODE_SELECTOR").forEach { name ->
            listOf("n1", "=n1", "a=b,").forEach {
                val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + (name to it)) }
                assertTrue(name in error.message.orEmpty())
            }
        }
    }

    @Test
    fun `tolerations parse from taints in kubectl form`() {
        val config = Config.fromEnv(
            minimalEnv + mapOf(
                "LIFTGATE_WORKLOAD_TOLERATIONS" to "liftgate.dev/pool=workloads:NoSchedule, dedicated",
                "LIFTGATE_BUILD_TOLERATIONS" to "liftgate.dev/pool:NoExecute",
            ),
        )
        assertEquals(
            listOf(
                TolerationBuilder().withKey("liftgate.dev/pool").withOperator("Equal").withValue("workloads").withEffect("NoSchedule").build(),
                TolerationBuilder().withKey("dedicated").withOperator("Exists").build(),
            ),
            config.workloadTolerations,
        )
        assertEquals(listOf(TolerationBuilder().withKey("liftgate.dev/pool").withOperator("Exists").withEffect("NoExecute").build()), config.buildTolerations)
    }

    @Test
    fun `malformed tolerations name the variable`() {
        listOf("pool=build:Sometimes", ":NoSchedule", "a=b,", "a b").forEach {
            val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_BUILD_TOLERATIONS" to it)) }
            assertTrue("LIFTGATE_BUILD_TOLERATIONS" in error.message.orEmpty())
        }
    }

    @Test
    fun `reconciling roles refuse to start without a runtime class unless runc is allowed`() {
        listOf(minimalEnv, minimalEnv + github + ("LIFTGATE_ROLE" to "all")).map { it - "LIFTGATE_RUNTIME_CLASS" }.forEach { env ->
            val error = assertFailsWith<IllegalStateException> { Config.fromEnv(env) }
            assertTrue("LIFTGATE_RUNTIME_CLASS" in error.message.orEmpty() && "LIFTGATE_ALLOW_RUNC" in error.message.orEmpty())
            assertNull(Config.fromEnv(env + ("LIFTGATE_ALLOW_RUNC" to "true")).runtimeClass)
        }
        listOf("builder", "meter").forEach { assertNull(Config.fromEnv(minimalEnv + github - "LIFTGATE_RUNTIME_CLASS" + ("LIFTGATE_ROLE" to it)).runtimeClass) }
    }

    @Test
    fun `github credentials, runtime class and leader election parse`() {
        val config = Config.fromEnv(
            minimalEnv + github + mapOf(
                "LIFTGATE_ROLE" to "all",
                "LIFTGATE_LEADER_ELECTION" to "off",
                "LIFTGATE_RUNTIME_CLASS" to "runsc-debug",
            ),
        )
        assertEquals(Role.ALL, config.role)
        assertEquals(GitHubConfig("1", "pem", "wh", "cid", "cs"), config.github)
        assertFalse(config.leaderElection)
        assertEquals("runsc-debug", config.runtimeClass)
    }

    @Test
    fun `the client ip header needs trusted proxy cidrs`() {
        val config = Config.fromEnv(minimalEnv + mapOf("LIFTGATE_CLIENT_IP_HEADER" to "CF-Connecting-IP", "LIFTGATE_TRUSTED_PROXY_CIDRS" to "10.0.0.0/8, fd00::/8"))
        assertEquals("CF-Connecting-IP", config.clientIpHeader)
        assertEquals(2, config.trustedProxyCidrs.size)
        listOf(
            mapOf("LIFTGATE_CLIENT_IP_HEADER" to "CF-Connecting-IP") to "LIFTGATE_TRUSTED_PROXY_CIDRS",
            mapOf("LIFTGATE_TRUSTED_PROXY_CIDRS" to "10.0.0.0") to "LIFTGATE_TRUSTED_PROXY_CIDRS",
            mapOf("LIFTGATE_TRUSTED_PROXY_CIDRS" to "10.0.0.0/33") to "LIFTGATE_TRUSTED_PROXY_CIDRS",
        ).forEach { (env, name) ->
            val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + env) }
            assertTrue(name in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `oauth providers exist only when their client is configured`() {
        val none = Config.fromEnv(minimalEnv)
        assertEquals(listOf(null, null, null), listOf(none.google, none.gitlab, none.bitbucket))
        assertEquals("https://gitlab.com", none.gitlabUrl)
        assertTrue(none.gitlabTrustEmail)
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
        assertFalse(config.gitlabTrustEmail)
        assertTrue(Config.fromEnv(minimalEnv + mapOf("LIFTGATE_GITLAB_URL" to "https://git.example", "LIFTGATE_GITLAB_TRUST_EMAIL" to "true")).gitlabTrustEmail)
        assertNull(config.bitbucket)
        val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_BITBUCKET_CLIENT_ID" to "b")) }
        assertTrue("LIFTGATE_BITBUCKET_CLIENT_SECRET" in error.message.orEmpty())
    }
}
