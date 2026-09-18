package dev.liftgate.auth

import dev.liftgate.db.now
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.notFound
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.ParametersBuilder
import io.ktor.http.formUrlEncode
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

const val GITHUB = "github"

fun pkceChallenge(verifier: String) = ApiTokens.hash(verifier)

/**
 * @author Dean
 * @date 9/18/2026
 */
data class VerifiedIdentity(
    val provider: String,
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val login: String? = null,
    val name: String? = null,
    val avatarUrl: String? = null,
)

/**
 * @author Dean
 * @date 9/18/2026
 */
data class OAuthTokens(val accessToken: String, val refreshToken: String?, val expiresAt: OffsetDateTime?)

/**
 * @author Dean
 * @date 9/18/2026
 */
class OAuthProvider(
    val id: String,
    val clientId: String,
    val clientSecret: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val scope: String?,
    val basicAuth: Boolean = false,
    val profile: suspend HttpClient.(accessToken: String) -> VerifiedIdentity,
)

/**
 * @author Dean
 * @date 9/18/2026
 */
class OAuth(private val client: HttpClient, private val publicUrl: String, providers: List<OAuthProvider>) {
    val providers = providers.associateBy { it.id }

    fun provider(id: String) = providers[id] ?: notFound("sign-in provider")

    fun loginUrl(provider: OAuthProvider, state: String, verifier: String) = provider.authorizeUrl + "?" + parameters {
        append("response_type", "code")
        append("client_id", provider.clientId)
        append("redirect_uri", redirectUri(provider))
        provider.scope?.let { append("scope", it) }
        append("state", state)
        append("code_challenge", pkceChallenge(verifier))
        append("code_challenge_method", "S256")
    }.formUrlEncode()

    suspend fun exchange(provider: OAuthProvider, code: String, verifier: String): OAuthTokens = token(provider) {
        append("grant_type", "authorization_code")
        append("code", code)
        append("redirect_uri", redirectUri(provider))
        append("code_verifier", verifier)
    } ?: throw LiftgateException(HttpStatusCode.Unauthorized, "oauth_failed", "${provider.id} did not return an access token")

    suspend fun refresh(provider: OAuthProvider, refreshToken: String): OAuthTokens? = token(provider) {
        append("grant_type", "refresh_token")
        append("refresh_token", refreshToken)
    }

    suspend fun identity(provider: OAuthProvider, tokens: OAuthTokens) = provider.profile(client, tokens.accessToken)

    private fun redirectUri(provider: OAuthProvider) = "$publicUrl/api/v1/auth/${provider.id}/callback"

    private suspend fun token(provider: OAuthProvider, grant: ParametersBuilder.() -> Unit): OAuthTokens? {
        val response = client.submitForm(provider.tokenUrl, parameters {
            grant()
            if (!provider.basicAuth) {
                append("client_id", provider.clientId)
                append("client_secret", provider.clientSecret)
            }
        }) {
            accept(ContentType.Application.Json)
            if (provider.basicAuth) basicAuth(provider.clientId, provider.clientSecret)
        }.body<TokenResponse>()
        return response.accessToken?.let { OAuthTokens(it, response.refreshToken, response.expiresIn?.let(now()::plusSeconds)) }
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
    )
}
