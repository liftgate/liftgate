package dev.liftgate.auth

import dev.liftgate.config.Config
import dev.liftgate.config.GitHubConfig
import dev.liftgate.config.OAuthClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/18/2026
 */
object OAuthProviders {
    fun enabled(config: Config) = listOfNotNull(
        config.github?.let(::github),
        config.google?.let(::google),
        config.gitlab?.let { gitlab(it, config.gitlabUrl) },
        config.bitbucket?.let(::bitbucket),
    )

    fun github(config: GitHubConfig) = OAuthProvider(
        GITHUB, config.clientId, config.clientSecret,
        "https://github.com/login/oauth/authorize", "https://github.com/login/oauth/access_token", "read:user user:email",
    ) { token ->
        val user = get("https://api.github.com/user") { bearerAuth(token) }.body<GitHubUser>()
        val primary = get("https://api.github.com/user/emails") { bearerAuth(token) }
            .takeIf { it.status.isSuccess() }
            ?.body<List<GitHubEmail>>()
            ?.firstOrNull { it.primary && it.verified }
        VerifiedIdentity(GITHUB, user.id.toString(), primary?.email ?: user.email, primary != null, user.login, user.name, user.avatarUrl)
    }

    fun google(client: OAuthClient) = OAuthProvider(
        "google", client.clientId, client.clientSecret,
        "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token", "openid email profile",
    ) { token ->
        get("https://openidconnect.googleapis.com/v1/userinfo") { bearerAuth(token) }.body<GoogleUser>()
            .let { VerifiedIdentity("google", it.sub, it.email, it.emailVerified, name = it.name, avatarUrl = it.picture) }
    }

    fun gitlab(client: OAuthClient, url: String) = OAuthProvider(
        "gitlab", client.clientId, client.clientSecret,
        "$url/oauth/authorize", "$url/oauth/token", "read_user",
    ) { token ->
        get("$url/api/v4/user") { bearerAuth(token) }.body<GitLabUser>()
            .let { VerifiedIdentity("gitlab", it.id.toString(), it.email, it.email != null && it.confirmedAt != null, it.username, it.name, it.avatarUrl) }
    }

    fun bitbucket(client: OAuthClient) = OAuthProvider(
        "bitbucket", client.clientId, client.clientSecret,
        "https://bitbucket.org/site/oauth2/authorize", "https://bitbucket.org/site/oauth2/access_token", null, basicAuth = true,
    ) { token ->
        val user = get("https://api.bitbucket.org/2.0/user") { bearerAuth(token) }.body<BitbucketUser>()
        val primary = get("https://api.bitbucket.org/2.0/user/emails") { bearerAuth(token) }.body<BitbucketEmails>().values.firstOrNull { it.isPrimary }
        VerifiedIdentity("bitbucket", user.uuid, primary?.email, primary?.isConfirmed == true, user.username, user.displayName, user.links.avatar?.href)
    }

    @Serializable
    private data class GitHubUser(
        val id: Long,
        val login: String,
        val name: String? = null,
        val email: String? = null,
        @SerialName("avatar_url") val avatarUrl: String? = null,
    )

    @Serializable
    private data class GitHubEmail(val email: String, val primary: Boolean, val verified: Boolean)

    @Serializable
    private data class GoogleUser(
        val sub: String,
        val email: String? = null,
        @SerialName("email_verified") val emailVerified: Boolean = false,
        val name: String? = null,
        val picture: String? = null,
    )

    @Serializable
    private data class GitLabUser(
        val id: Long,
        val username: String,
        val name: String? = null,
        val email: String? = null,
        @SerialName("confirmed_at") val confirmedAt: String? = null,
        @SerialName("avatar_url") val avatarUrl: String? = null,
    )

    @Serializable
    private data class BitbucketUser(
        val uuid: String,
        val username: String? = null,
        @SerialName("display_name") val displayName: String? = null,
        val links: Links = Links(),
    ) {
        @Serializable
        data class Links(val avatar: Href? = null)

        @Serializable
        data class Href(val href: String)
    }

    @Serializable
    private data class BitbucketEmails(val values: List<Email> = emptyList()) {
        @Serializable
        data class Email(val email: String, @SerialName("is_primary") val isPrimary: Boolean, @SerialName("is_confirmed") val isConfirmed: Boolean)
    }
}
