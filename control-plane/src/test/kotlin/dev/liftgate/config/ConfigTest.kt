package dev.liftgate.config

import dev.liftgate.domain.DomainNames
import dev.liftgate.minimalEnv
import dev.liftgate.org.Plan
import dev.liftgate.org.Plans
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
        assertEquals("http://localhost:9090", Config.fromEnv(minimalEnv + ("LIFTGATE_HTTP_PORT" to "9090")).internalUrl)
        assertEquals("nats://localhost:4222", config.natsUrl)
        assertEquals("liftgate.app", config.deployDomain)
        assertEquals("liftgate-system", config.gatewayNamespace)
        assertEquals(32, config.secretsMasterKey?.size)
        assertTrue(config.leaderElection)
        assertFalse(config.hazelcastKubernetes)
        assertNull(config.github)
        assertEquals("gvisor", config.runtimeClass)
        assertTrue(config.nodeSelector.isEmpty())
        assertTrue(config.workloadTolerations.isEmpty() && config.buildTolerations.isEmpty())
        assertFalse(config.registryInsecure)
        assertEquals("letsencrypt", config.certIssuer)
        assertEquals(3, config.databasePoolSize)
    }

    @Test
    fun `plans parse strictly, default to unlimited and must contain the default plan`() {
        assertEquals(Plans(), Config.fromEnv(minimalEnv).plans)
        assertNull(Config.fromEnv(minimalEnv).customDomainsMax)
        val config = Config.fromEnv(
            minimalEnv + mapOf(
                "LIFTGATE_PLANS" to """{"free":{"projects":3,"cpuRequestRatio":0.25,"egressBandwidth":"20M","udp":false},"unlimited":{}}""",
                "LIFTGATE_DEFAULT_PLAN" to "free",
                "LIFTGATE_CUSTOM_DOMAINS_MAX" to "100",
            ),
        )
        assertEquals(Plan(projects = 3, cpuRequestRatio = 0.25, egressBandwidth = "20M", udp = false), config.plans.of("default"))
        assertEquals(Plan(), config.plans.of("unlimited"))
        assertEquals(100, config.customDomainsMax)
        listOf(
            mapOf("LIFTGATE_PLANS" to """{"free":{"project":3}}""", "LIFTGATE_DEFAULT_PLAN" to "free") to "LIFTGATE_PLANS",
            mapOf("LIFTGATE_PLANS" to """{"free":{"cpuRequestRatio":2}}""", "LIFTGATE_DEFAULT_PLAN" to "free") to "cpuRequestRatio",
            mapOf("LIFTGATE_PLANS" to """{"free":{"projects":-1}}""", "LIFTGATE_DEFAULT_PLAN" to "free") to "negative",
            mapOf("LIFTGATE_PLANS" to """{"free":{"egressBandwidth":"fast"}}""", "LIFTGATE_DEFAULT_PLAN" to "free") to "egressBandwidth",
            mapOf("LIFTGATE_PLANS" to """{"free":{"concurrentBuilds":1}}""", "LIFTGATE_DEFAULT_PLAN" to "free") to "buildsPerHour",
            mapOf("LIFTGATE_PLANS" to """{"free":{},"default":{}}""", "LIFTGATE_DEFAULT_PLAN" to "free") to "LIFTGATE_PLANS",
            mapOf("LIFTGATE_PLANS" to """{"free":{}}""") to "LIFTGATE_DEFAULT_PLAN",
            mapOf("LIFTGATE_DEFAULT_PLAN" to "free") to "LIFTGATE_DEFAULT_PLAN",
            mapOf("LIFTGATE_CUSTOM_DOMAINS_MAX" to "lots") to "LIFTGATE_CUSTOM_DOMAINS_MAX",
        ).forEach { (env, name) -> assertTrue(name in assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + env) }.message.orEmpty(), env.toString()) }
    }

    @Test
    fun `edge mode needs a zone and a token, reaches only the api and reconciler, and caps custom domains at 100`() {
        val edge = mapOf("LIFTGATE_CLOUDFLARE_ZONE_ID" to "zone", "LIFTGATE_CLOUDFLARE_API_TOKEN" to "token")
        assertNull(Config.fromEnv(minimalEnv).cloudflare)
        val reconciler = Config.fromEnv(minimalEnv + edge)
        assertEquals(CloudflareConfig("zone", "token"), reconciler.cloudflare)
        assertEquals(100, reconciler.customDomainsMax)
        val api = Config.fromEnv(minimalEnv + edge + mapOf("LIFTGATE_ROLE" to "api", "LIFTGATE_CUSTOM_DOMAINS_MAX" to "50"))
        assertEquals(CloudflareConfig("zone", "token") to 50, api.cloudflare to api.customDomainsMax)
        assertNull(Config.fromEnv(minimalEnv + ("LIFTGATE_ROLE" to "meter") + ("LIFTGATE_CLOUDFLARE_ZONE_ID" to "zone")).cloudflare)
        listOf(edge - "LIFTGATE_CLOUDFLARE_API_TOKEN" to "LIFTGATE_CLOUDFLARE_API_TOKEN", edge - "LIFTGATE_CLOUDFLARE_ZONE_ID" to "LIFTGATE_CLOUDFLARE_ZONE_ID").forEach { (env, name) ->
            assertTrue(name in assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + env) }.message.orEmpty(), env.toString())
        }
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
    fun `only the roles that open sealed values need the master key`() {
        val keyless = minimalEnv - "LIFTGATE_SECRETS_MASTER_KEY"
        listOf("meter", "migrate").forEach { role -> assertNull(Config.fromEnv(keyless + ("LIFTGATE_ROLE" to role)).secretsMasterKey) }
        listOf("api", "all", "builder").forEach { role ->
            val error = assertFailsWith<IllegalStateException> { Config.fromEnv(keyless + github + ("LIFTGATE_ROLE" to role)) }
            assertTrue("LIFTGATE_SECRETS_MASTER_KEY" in error.message.orEmpty())
        }
    }

    @Test
    fun `the builder needs the GitHub App, the api runs without it, and only the api reads GitHub secrets`() {
        val builderError = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_ROLE" to "builder")) }
        assertTrue("LIFTGATE_GITHUB_APP_ID" in builderError.message.orEmpty())
        val api = Config.fromEnv(minimalEnv + ("LIFTGATE_ROLE" to "api"))
        assertEquals(listOf(null, null, null), listOf(api.github, api.githubWebhookSecret, api.githubClient))
        val builder = Config.fromEnv(minimalEnv + github - "LIFTGATE_GITHUB_WEBHOOK_SECRET" - "LIFTGATE_GITHUB_CLIENT_SECRET" + ("LIFTGATE_ROLE" to "builder"))
        assertEquals(GitHubConfig("1", "pem"), builder.github)
        assertEquals(listOf(null, null), listOf(builder.githubWebhookSecret, builder.githubClient))
        val reconciler = Config.fromEnv(minimalEnv + github - "LIFTGATE_GITHUB_APP_PRIVATE_KEY" - "LIFTGATE_GITHUB_CLIENT_SECRET" + ("LIFTGATE_GOOGLE_CLIENT_ID" to "g"))
        assertEquals(listOf(null, null, null), listOf(reconciler.github, reconciler.githubClient, reconciler.google))
        val apiError = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + github - "LIFTGATE_GITHUB_WEBHOOK_SECRET" + ("LIFTGATE_ROLE" to "api")) }
        assertTrue("LIFTGATE_GITHUB_WEBHOOK_SECRET" in apiError.message.orEmpty())
    }

    @Test
    fun `leader election is kubernetes or off`() {
        assertFalse(Config.fromEnv(minimalEnv + ("LIFTGATE_LEADER_ELECTION" to "off")).leaderElection)
        val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_LEADER_ELECTION" to "false")) }
        assertTrue("LIFTGATE_LEADER_ELECTION" in error.message.orEmpty())
    }

    @Test
    fun `an https public url needs a database password`() {
        val https = minimalEnv + ("LIFTGATE_PUBLIC_URL" to "https://liftgate.example.com")
        val error = assertFailsWith<IllegalStateException> { Config.fromEnv(https) }
        assertTrue("LIFTGATE_DATABASE_PASSWORD" in error.message.orEmpty())
        assertEquals("s3cret", Config.fromEnv(https + ("LIFTGATE_DATABASE_PASSWORD" to "s3cret")).databasePassword)
        assertEquals("liftgate", Config.fromEnv(minimalEnv).databasePassword)
    }

    @Test
    fun `the api gets the larger connection pool and the issuer is configurable`() {
        assertEquals(10, Config.fromEnv(minimalEnv + ("LIFTGATE_ROLE" to "api")).databasePoolSize)
        val config = Config.fromEnv(minimalEnv + mapOf("LIFTGATE_DATABASE_POOL_SIZE" to "5", "LIFTGATE_CERT_ISSUER" to "zerossl"))
        assertEquals(5 to "zerossl", config.databasePoolSize to config.certIssuer)
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
        assertEquals(GitHubConfig("1", "pem"), config.github)
        assertEquals("wh" to OAuthClient("cid", "cs"), config.githubWebhookSecret to config.githubClient)
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
        val api = minimalEnv + ("LIFTGATE_ROLE" to "api")
        val none = Config.fromEnv(api)
        assertEquals(listOf(null, null, null), listOf(none.google, none.gitlab, none.bitbucket))
        assertEquals("https://gitlab.com", none.gitlabUrl)
        assertTrue(none.gitlabTrustEmail)
        val config = Config.fromEnv(
            api + mapOf(
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
        val error = assertFailsWith<IllegalStateException> { Config.fromEnv(api + ("LIFTGATE_BITBUCKET_CLIENT_ID" to "b")) }
        assertTrue("LIFTGATE_BITBUCKET_CLIENT_SECRET" in error.message.orEmpty())
    }

    @Test
    fun `a gitlab url without https stops startup`() {
        listOf("http://git.example", "git.example", "https://").forEach {
            val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_GITLAB_URL" to it)) }
            assertTrue("LIFTGATE_GITLAB_URL" in error.message.orEmpty(), it)
        }
    }

    @Test
    fun `a deploy domain that leaves no room for a generated label stops startup`() {
        val longest = "a".repeat(DomainNames.MAX_DEPLOY_DOMAIN - 4) + ".app"
        assertEquals(longest, Config.fromEnv(minimalEnv + ("LIFTGATE_DEPLOY_DOMAIN" to longest)).deployDomain)
        val error = assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_DEPLOY_DOMAIN" to "a$longest")) }
        assertTrue("LIFTGATE_DEPLOY_DOMAIN" in error.message.orEmpty())
    }

    @Test
    fun `token registry auth requires its signing material only where tokens are served`() {
        assertFalse(Config.fromEnv(minimalEnv).registryTokenAuth)
        val reconciler = Config.fromEnv(minimalEnv + ("LIFTGATE_REGISTRY_AUTH" to "token"))
        assertTrue(reconciler.registryTokenAuth)
        assertNull(reconciler.registryTokens)
        assertNull(reconciler.registryJanitorPassword)
        val api = minimalEnv + github + mapOf("LIFTGATE_ROLE" to "api", "LIFTGATE_REGISTRY_AUTH" to "token")
        assertTrue("LIFTGATE_REGISTRY_TOKEN_KEY" in assertFailsWith<IllegalStateException> { Config.fromEnv(api) }.message.orEmpty())
        val signing = mapOf("LIFTGATE_REGISTRY_TOKEN_KEY" to "key", "LIFTGATE_REGISTRY_TOKEN_CERTIFICATE" to "cert", "LIFTGATE_REGISTRY_PULL_PASSWORD" to "pull")
        assertTrue("LIFTGATE_REGISTRY_JANITOR_PASSWORD" in assertFailsWith<IllegalStateException> { Config.fromEnv(api + signing) }.message.orEmpty())
        assertEquals(RegistryTokenConfig("key", "cert", "pull"), Config.fromEnv(api + signing + ("LIFTGATE_REGISTRY_JANITOR_PASSWORD" to "janitor")).registryTokens)
        assertTrue("LIFTGATE_REGISTRY_AUTH" in assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_REGISTRY_AUTH" to "none")) }.message.orEmpty())
    }

    @Test
    fun `the builder gets the registry janitor password and no signing key`() {
        val builder = minimalEnv + github + mapOf("LIFTGATE_ROLE" to "builder", "LIFTGATE_REGISTRY_AUTH" to "token")
        assertTrue("LIFTGATE_REGISTRY_JANITOR_PASSWORD" in assertFailsWith<IllegalStateException> { Config.fromEnv(builder) }.message.orEmpty())
        val config = Config.fromEnv(builder + ("LIFTGATE_REGISTRY_JANITOR_PASSWORD" to "janitor"))
        assertEquals(null to "janitor", config.registryTokens to config.registryJanitorPassword)
    }

    @Test
    fun `the build log byte limit must be a positive number`() {
        assertEquals(536_870_912L, Config.fromEnv(minimalEnv).buildLogsMaxBytes)
        listOf("0", "-1", "lots").forEach {
            assertTrue("LIFTGATE_BUILD_LOGS_MAX_BYTES" in assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_BUILD_LOGS_MAX_BYTES" to it)) }.message.orEmpty(), it)
        }
    }

    @Test
    fun `denied egress cidrs are ipv4 cidrs and default to none`() {
        assertEquals(emptyList(), Config.fromEnv(minimalEnv).deniedEgressCidrs)
        assertEquals(listOf("203.0.113.7/32", "203.0.113.0/24"), Config.fromEnv(minimalEnv + ("LIFTGATE_DENIED_EGRESS_CIDRS" to "203.0.113.7/32, 203.0.113.0/24")).deniedEgressCidrs)
        listOf("203.0.113.7", "10.0.0.0/33", "fd00::/8").forEach {
            assertTrue("LIFTGATE_DENIED_EGRESS_CIDRS" in assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_DENIED_EGRESS_CIDRS" to it)) }.message.orEmpty(), it)
        }
    }

    @Test
    fun `database backups need their keys on the reconciler and take ipv4 cidr and port pairs`() {
        val backup = minimalEnv + ("LIFTGATE_DATABASE_BACKUP_DESTINATION" to "s3://tenants/")
        assertTrue("LIFTGATE_DATABASE_BACKUP_ACCESS_KEY_ID" in assertFailsWith<IllegalStateException> { Config.fromEnv(backup) }.message.orEmpty())
        assertNull(Config.fromEnv(backup + ("LIFTGATE_ROLE" to "api")).databaseBackup?.accessKeyId)
        val keys = backup + mapOf("LIFTGATE_DATABASE_BACKUP_ACCESS_KEY_ID" to "key", "LIFTGATE_DATABASE_BACKUP_SECRET_ACCESS_KEY" to "secret")
        assertEquals(listOf("192.0.2.10/32" to 3900), Config.fromEnv(keys + ("LIFTGATE_DATABASE_BACKUP_EGRESS" to "192.0.2.10/32:3900")).databaseBackup?.egress)
        listOf("192.0.2.10/32", "192.0.2.10/32:0", "fd00::/8:3900").forEach {
            assertTrue("LIFTGATE_DATABASE_BACKUP_EGRESS" in assertFailsWith<IllegalStateException> { Config.fromEnv(keys + ("LIFTGATE_DATABASE_BACKUP_EGRESS" to it)) }.message.orEmpty(), it)
        }
        assertNull(Config.fromEnv(minimalEnv).databaseBackup)
    }
}
