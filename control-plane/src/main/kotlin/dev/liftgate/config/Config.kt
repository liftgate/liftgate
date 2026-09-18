package dev.liftgate.config

import io.ktor.http.Url
import java.util.Base64

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
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun optional(name: String) = env["LIFTGATE_$name"]?.takeIf { it.isNotBlank() }
            fun required(name: String) = optional(name) ?: error("LIFTGATE_$name is required")
            fun text(name: String, default: String) = optional(name) ?: default
            fun oauthClient(name: String) = optional("${name}_CLIENT_ID")?.let { OAuthClient(it, required("${name}_CLIENT_SECRET")) }

            val roleName = text("ROLE", "all")
            val role = Role.entries.firstOrNull { it.name.equals(roleName, ignoreCase = true) }
                ?: error("LIFTGATE_ROLE must be one of api, reconciler, builder, meter, all")
            val github = if (role in setOf(Role.API, Role.BUILDER, Role.ALL) || optional("GITHUB_APP_ID") != null) GitHubConfig(
                appId = required("GITHUB_APP_ID"),
                privateKeyPem = required("GITHUB_APP_PRIVATE_KEY"),
                webhookSecret = required("GITHUB_WEBHOOK_SECRET"),
                clientId = required("GITHUB_CLIENT_ID"),
                clientSecret = required("GITHUB_CLIENT_SECRET"),
            ) else null
            val dashboardUrl = text("DASHBOARD_URL", "http://localhost:3000")
            val encodedKey = required("SECRETS_MASTER_KEY")
            val masterKey = runCatching { Base64.getDecoder().decode(encodedKey) }.getOrNull()?.takeIf { it.size == 32 }
                ?: error("LIFTGATE_SECRETS_MASTER_KEY must be the base64 of 32 random bytes")
            val nodeSelector = optional("NODE_SELECTOR")?.split(',')?.associate { pair ->
                pair.split('=', limit = 2).map(String::trim).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { (key, value) -> key to value }
                    ?: error("LIFTGATE_NODE_SELECTOR must be key=value[,key=value]")
            }.orEmpty()

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
                gitlabUrl = text("GITLAB_URL", "https://gitlab.com").trimEnd('/'),
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
