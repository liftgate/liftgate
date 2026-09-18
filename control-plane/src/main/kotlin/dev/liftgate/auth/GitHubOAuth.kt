package dev.liftgate.auth

import dev.liftgate.config.GitHubConfig
import dev.liftgate.http.LiftgateException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class GitHubUser(
    val id: Long,
    val login: String,
    val name: String? = null,
    val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/**
 * @author Dean
 * @date 9/17/2026
 */
class GitHubOAuth(private val config: GitHubConfig, private val client: HttpClient, private val redirectUri: String) {
    fun loginUrl(state: String) = "https://github.com/login/oauth/authorize?" + parameters {
        append("client_id", config.clientId)
        append("redirect_uri", redirectUri)
        append("scope", "read:user user:email")
        append("state", state)
    }.formUrlEncode()

    suspend fun exchange(code: String): Pair<GitHubUser, String> {
        val token = client.submitForm("https://github.com/login/oauth/access_token", parameters {
            append("client_id", config.clientId)
            append("client_secret", config.clientSecret)
            append("code", code)
            append("redirect_uri", redirectUri)
        }) { accept(ContentType.Application.Json) }.body<AccessToken>().accessToken
            ?: throw LiftgateException(HttpStatusCode.Unauthorized, "oauth_failed", "GitHub did not return an access token")
        val user = client.get("https://api.github.com/user") { bearerAuth(token) }.body<GitHubUser>()
        val email = user.email
            ?: client.get("https://api.github.com/user/emails") { bearerAuth(token) }.body<List<Email>>().firstOrNull { it.primary }?.email
        return user.copy(email = email) to token
    }

    @Serializable
    private data class AccessToken(@SerialName("access_token") val accessToken: String? = null)

    @Serializable
    private data class Email(val email: String, val primary: Boolean)
}
