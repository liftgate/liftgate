package dev.liftgate.config

import io.fabric8.kubernetes.api.model.Toleration
import io.fabric8.kubernetes.api.model.TolerationBuilder
import io.ktor.http.Url
import io.netty.handler.ipfilter.IpFilterRuleType
import io.netty.handler.ipfilter.IpSubnetFilterRule
import java.util.Base64

private const val GITLAB_COM = "https://gitlab.com"

/**
 * @author Dean
 * @date 9/17/2026
 */
enum class Role { API, RECONCILER, BUILDER, METER, ALL }

/**
 * @author Dean
 * @date 9/17/2026
 */
data class GitHubConfig(
    val appId: String,
    val privateKeyPem: String,
    val webhookSecret: String,
    val clientId: String,
    val clientSecret: String,
)

/**
 * @author Dean
 * @date 9/18/2026
 */
data class OAuthClient(val clientId: String, val clientSecret: String)

/**
 * @author Dean
 * @date 9/18/2026
 */
data class EmailConfig(val host: String, val port: Int, val implicitTls: Boolean, val user: String?, val password: String?, val from: String)

/**
 * @author Dean
 * @date 9/17/2026
 */
data class Config(
    val role: Role,
    val httpPort: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val natsUrl: String,
    val hazelcastCluster: String,
    val hazelcastKubernetes: Boolean,
    val publicUrl: String,
    val dashboardUrl: String,
    val trustedProxies: Int,
    val deployDomain: String,
    val github: GitHubConfig?,
    val google: OAuthClient?,
    val gitlab: OAuthClient?,
    val gitlabUrl: String,
    val bitbucket: OAuthClient?,
    val webauthnRpId: String,
    val webauthnRpName: String,
    val email: EmailConfig?,
    val secretsMasterKey: ByteArray,
    val registry: String,
    val buildImage: String,
    val buildNamespace: String,
    val runtimeClass: String?,
    val nodeSelector: Map<String, String>,
    val registryInsecure: Boolean,
    val gatewayNamespace: String,
    val gatewayName: String,
    val prometheusUrl: String,
    val leaderElection: Boolean,
    val natsReplicas: Int,
    val signup: Signup,
    val signupAllow: List<String>,
    val termsUrl: String?,
    val privacyUrl: String?,
    val aupUrl: String?,
    val gitlabTrustEmail: Boolean,
    val customDomainsEnabled: Boolean,
    val clientIpHeader: String?,
    val trustedProxyCidrs: List<IpSubnetFilterRule>,
    val workloadNodeSelector: Map<String, String>,
    val workloadTolerations: List<Toleration>,
    val registryTokenAuth: Boolean,
    val registryTokens: RegistryTokenConfig?,
    val buildNodeSelector: Map<String, String>,
    val buildTolerations: List<Toleration>,
) {
    companion object {
        private val taint = Regex("""([\w./-]+)(?:=([\w.-]*))?(?::(NoSchedule|PreferNoSchedule|NoExecute))?""")

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun optional(name: String) = env["LIFTGATE_$name"]?.takeIf { it.isNotBlank() }
            fun required(name: String) = optional(name) ?: error("LIFTGATE_$name is required")
            fun text(name: String, default: String) = optional(name) ?: default
            fun oauthClient(name: String) = optional("${name}_CLIENT_ID")?.let { OAuthClient(it, required("${name}_CLIENT_SECRET")) }
            fun tolerations(name: String) = optional(name)?.split(',')?.map {
                val match = taint.matchEntire(it.trim()) ?: error("LIFTGATE_$name must be key[=value][:effect][,key[=value][:effect]]")
                val value = match.groups[2]?.value
                TolerationBuilder().withKey(match.groupValues[1]).withOperator(if (value == null) "Exists" else "Equal").withValue(value).withEffect(match.groups[3]?.value).build()
            }.orEmpty()

            val roleName = text("ROLE", "all")
            val role = Role.entries.firstOrNull { it.name.equals(roleName, ignoreCase = true) }
                ?: error("LIFTGATE_ROLE must be one of api, reconciler, builder, meter, all")
            check(role !in setOf(Role.RECONCILER, Role.ALL) || optional("RUNTIME_CLASS") != null || text("ALLOW_RUNC", "false").toBoolean()) {
                "LIFTGATE_RUNTIME_CLASS is required for the reconciler; set LIFTGATE_ALLOW_RUNC=true to run tenant pods under runc without a sandbox"
            }
            val github = if (role in setOf(Role.API, Role.BUILDER, Role.ALL) || optional("GITHUB_APP_ID") != null) GitHubConfig(
                appId = required("GITHUB_APP_ID"),
                privateKeyPem = required("GITHUB_APP_PRIVATE_KEY"),
                webhookSecret = required("GITHUB_WEBHOOK_SECRET"),
                clientId = required("GITHUB_CLIENT_ID"),
                clientSecret = required("GITHUB_CLIENT_SECRET"),
            ) else null
            val dashboardUrl = text("DASHBOARD_URL", "http://localhost:3000")
            val gitlabUrl = text("GITLAB_URL", GITLAB_COM).trimEnd('/')
            val encodedKey = required("SECRETS_MASTER_KEY")
            val masterKey = runCatching { Base64.getDecoder().decode(encodedKey) }.getOrNull()?.takeIf { it.size == 32 }
                ?: error("LIFTGATE_SECRETS_MASTER_KEY must be the base64 of 32 random bytes")
            fun selector(name: String) = optional(name)?.split(',')?.associate { pair ->
                pair.split('=', limit = 2).map(String::trim).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { (key, value) -> key to value }
                    ?: error("LIFTGATE_$name must be key=value[,key=value]")
            }
            val nodeSelector = selector("NODE_SELECTOR").orEmpty()
            val signup = text("SIGNUP", "approval").let { name -> Signup.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                ?: error("LIFTGATE_SIGNUP must be one of open, approval, closed")
            val allowEntry = Regex("github:[a-z0-9-]+|@[^@\\s]+|[^@\\s]+@[^@\\s]+")
            val signupAllow = optional("SIGNUP_ALLOW")?.split(',')?.map { it.trim().lowercase() }?.filter(String::isNotEmpty).orEmpty()
                .onEach { if (!allowEntry.matches(it)) error("LIFTGATE_SIGNUP_ALLOW entries must be emails, @domains or github:<login>, not $it") }
            val trustedProxyCidrs = optional("TRUSTED_PROXY_CIDRS")?.split(',')?.map {
                runCatching { IpSubnetFilterRule(it.trim(), IpFilterRuleType.ACCEPT) }.getOrElse { error("LIFTGATE_TRUSTED_PROXY_CIDRS must be CIDRs such as 10.0.0.0/8[,fd00::/8]") }
            }.orEmpty()
            val clientIpHeader = optional("CLIENT_IP_HEADER")?.also { if (trustedProxyCidrs.isEmpty()) error("LIFTGATE_CLIENT_IP_HEADER needs LIFTGATE_TRUSTED_PROXY_CIDRS") }
            val registryTokenAuth = when (text("REGISTRY_AUTH", "shared")) {
                "shared" -> false
                "token" -> true
                else -> error("LIFTGATE_REGISTRY_AUTH must be shared or token")
            }

            return Config(
                role = role,
                httpPort = text("HTTP_PORT", "8080").toInt(),
                databaseUrl = text("DATABASE_URL", "jdbc:postgresql://localhost:5432/liftgate"),
                databaseUser = text("DATABASE_USER", "liftgate"),
                databasePassword = text("DATABASE_PASSWORD", "liftgate"),
                natsUrl = text("NATS_URL", "nats://localhost:4222"),
                hazelcastCluster = text("HAZELCAST_CLUSTER", "liftgate"),
                hazelcastKubernetes = text("HAZELCAST_KUBERNETES", "false").toBoolean(),
                publicUrl = text("PUBLIC_URL", "http://localhost:8080"),
                dashboardUrl = dashboardUrl,
                trustedProxies = text("TRUSTED_PROXIES", "0").toIntOrNull()?.takeIf { it >= 0 } ?: error("LIFTGATE_TRUSTED_PROXIES must be a number of proxy hops"),
                deployDomain = text("DEPLOY_DOMAIN", "liftgate.app"),
                github = github,
                google = oauthClient("GOOGLE"),
                gitlab = oauthClient("GITLAB"),
                gitlabUrl = gitlabUrl,
                bitbucket = oauthClient("BITBUCKET"),
                webauthnRpId = text("WEBAUTHN_RP_ID", Url(dashboardUrl).host),
                webauthnRpName = text("WEBAUTHN_RP_NAME", "Liftgate"),
                email = optional("SMTP_URL")?.let { url -> optional("EMAIL_FROM")?.let { emailConfig(url, it) } },
                secretsMasterKey = masterKey,
                registry = text("REGISTRY", "registry.liftgate.internal"),
                buildImage = text("BUILD_IMAGE", "ghcr.io/liftgate/build-image:latest"),
                buildNamespace = text("BUILD_NAMESPACE", "liftgate-build"),
                runtimeClass = optional("RUNTIME_CLASS"),
                nodeSelector = nodeSelector,
                registryInsecure = text("REGISTRY_INSECURE", "false").toBoolean(),
                gatewayNamespace = text("GATEWAY_NAMESPACE", "liftgate-system"),
                gatewayName = text("GATEWAY_NAME", "liftgate"),
                prometheusUrl = text("PROMETHEUS_URL", "http://prometheus.liftgate-system:9090"),
                leaderElection = text("LEADER_ELECTION", "kubernetes") != "off",
                natsReplicas = text("NATS_REPLICAS", "1").toInt(),
                signup = signup,
                signupAllow = signupAllow,
                termsUrl = optional("TERMS_URL"),
                privacyUrl = optional("PRIVACY_URL"),
                aupUrl = optional("AUP_URL"),
                gitlabTrustEmail = text("GITLAB_TRUST_EMAIL", (gitlabUrl == GITLAB_COM).toString()).toBoolean(),
                customDomainsEnabled = text("CUSTOM_DOMAINS_ENABLED", "true").toBoolean(),
                clientIpHeader = clientIpHeader,
                trustedProxyCidrs = trustedProxyCidrs,
                workloadNodeSelector = selector("WORKLOAD_NODE_SELECTOR") ?: nodeSelector,
                workloadTolerations = tolerations("WORKLOAD_TOLERATIONS"),
                registryTokenAuth = registryTokenAuth,
                registryTokens = if (registryTokenAuth && role in setOf(Role.API, Role.ALL)) RegistryTokenConfig(
                    privateKeyPem = required("REGISTRY_TOKEN_KEY"),
                    certificatePem = required("REGISTRY_TOKEN_CERTIFICATE"),
                    pullPassword = required("REGISTRY_PULL_PASSWORD"),
                ) else null,
                buildNodeSelector = selector("BUILD_NODE_SELECTOR") ?: nodeSelector,
                buildTolerations = tolerations("BUILD_TOLERATIONS"),
            )
        }
    }
}

private fun emailConfig(smtpUrl: String, from: String): EmailConfig {
    val url = Url(smtpUrl)
    val implicitTls = when (url.protocol.name) {
        "smtps" -> true
        "smtp" -> false
        else -> error("LIFTGATE_SMTP_URL must start with smtp:// or smtps://")
    }
    val host = url.host.ifEmpty { error("LIFTGATE_SMTP_URL needs a host") }
    return EmailConfig(host, url.specifiedPort.takeIf { it != 0 } ?: if (implicitTls) 465 else 587, implicitTls, url.user, url.password, from)
}
