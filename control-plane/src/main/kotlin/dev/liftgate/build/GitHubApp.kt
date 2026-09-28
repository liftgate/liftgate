package dev.liftgate.build

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.liftgate.config.GitHubConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

private const val API = "https://api.github.com"

/**
 * @author Dean
 * @date 9/17/2026
 */
class GitHubApp(private val config: GitHubConfig, private val client: HttpClient) {
    private val key = rsaPrivateKey(config.privateKeyPem, "LIFTGATE_GITHUB_APP_PRIVATE_KEY")

    fun appJwt(): String = Instant.now().let {
        JWT.create().withIssuer(config.appId).withIssuedAt(it.minusSeconds(60)).withExpiresAt(it.plusSeconds(540)).sign(Algorithm.RSA256(null, key))
    }

    suspend fun installationToken(installationId: Long, repository: String, permissions: Map<String, String> = mapOf("contents" to "read", "metadata" to "read")): String =
        client.post("$API/app/installations/$installationId/access_tokens") {
            github(appJwt())
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(listOf(repository), permissions))
        }.body<AccessToken>().token

    suspend fun branchHead(installationId: Long, repoFullName: String, branch: String): Pair<String, String?> =
        client.get("$API/repos/$repoFullName/commits/$branch") { github(installationToken(installationId, repoFullName.substringAfter('/'))) }
            .body<Commit>().let { it.sha to it.commit.message }

    suspend fun canPush(installationToken: String, repoFullName: String, login: String): Boolean = try {
        client.get("$API/repos/$repoFullName/collaborators/$login/permission") { github(installationToken) }.body<CollaboratorPermission>().permission in setOf("admin", "write")
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound) false else throw e
    }

    suspend fun installation(userToken: String, repoFullName: String): Long? = try {
        client.get("$API/repos/$repoFullName") { github(userToken) }.body<Repository>().permissions.push.takeIf { it }
            ?.let { client.get("$API/repos/$repoFullName/installation") { github(appJwt()) }.body<Installation>().id }
    } catch (e: ResponseException) {
        null
    }

    suspend fun postStatus(installationId: Long, repoFullName: String, sha: String, status: CommitStatus) {
        client.post("$API/repos/$repoFullName/statuses/$sha") {
            github(installationToken(installationId, repoFullName.substringAfter('/'), mapOf("statuses" to "write")))
            contentType(ContentType.Application.Json)
            setBody(status)
        }
    }

    private fun HttpRequestBuilder.github(token: String) {
        expectSuccess = true
        bearerAuth(token)
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    @Serializable
    data class CommitStatus(val state: String, @SerialName("target_url") val targetUrl: String, val description: String, val context: String)

    @Serializable
    private data class TokenRequest(val repositories: List<String>, val permissions: Map<String, String>)

    @Serializable
    private data class AccessToken(val token: String)

    @Serializable
    private data class CollaboratorPermission(val permission: String)

    @Serializable
    private data class Installation(val id: Long)

    @Serializable
    private data class Repository(val permissions: Permissions = Permissions()) {
        @Serializable
        data class Permissions(val push: Boolean = false)
    }

    @Serializable
    private data class Commit(val sha: String, val commit: Details) {
        @Serializable
        data class Details(val message: String? = null)
    }
}
