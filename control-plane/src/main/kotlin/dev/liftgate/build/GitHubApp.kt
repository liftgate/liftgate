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
import io.ktor.client.request.parameter
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
private const val PER_PAGE = 100

/**
 * @author Dean
 * @date 9/17/2026
 */
class GitHubApp(private val config: GitHubConfig, private val client: HttpClient) {
    private val key = rsaPrivateKey(config.privateKeyPem, "LIFTGATE_GITHUB_APP_PRIVATE_KEY")

    @Volatile
    private var appUrl: String? = null

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

    suspend fun installation(userToken: String, repoFullName: String): Pair<Long, String>? = try {
        client.get("$API/repos/$repoFullName") { github(userToken) }.body<Repository>().takeIf { it.permissions.push }
            ?.let { client.get("$API/repos/$repoFullName/installation") { github(appJwt()) }.body<Installation>().id to it.defaultBranch }
    } catch (e: ResponseException) {
        null
    }

    suspend fun importable(userToken: String): Importable {
        val repositories = pages { page -> client.get("$API/user/installations") { github(userToken); paged(page) }.body<Installations>().installations }
            .flatMap { installation -> pages { page -> client.get("$API/user/installations/${installation.id}/repositories") { github(userToken); paged(page) }.body<Repositories>().repositories } }
            .filter { it.permissions.push }
            .sortedByDescending { it.pushedAt }
        return Importable(repositories.map { Importable.Repo(it.fullName, it.defaultBranch, it.private) }, "${appUrl()}/installations/new")
    }

    private suspend fun appUrl() = appUrl ?: client.get("$API/app") { github(appJwt()) }.body<AppLink>().htmlUrl.also { appUrl = it }

    private suspend fun <T> pages(fetch: suspend (Int) -> List<T>): List<T> {
        val all = mutableListOf<T>()
        var page = 1
        do {
            val batch = fetch(page++)
            all += batch
        } while (batch.size == PER_PAGE)
        return all
    }

    private fun HttpRequestBuilder.paged(page: Int) {
        parameter("per_page", PER_PAGE)
        parameter("page", page)
    }

    suspend fun postStatus(installationToken: String, repoFullName: String, sha: String, status: CommitStatus) {
        client.post("$API/repos/$repoFullName/statuses/$sha") {
            github(installationToken)
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
    data class Importable(val repositories: List<Repo>, val installUrl: String) {
        @Serializable
        data class Repo(val fullName: String, val defaultBranch: String, val private: Boolean)
    }

    @Serializable
    private data class Repository(
        val permissions: Permissions = Permissions(),
        @SerialName("full_name") val fullName: String = "",
        @SerialName("default_branch") val defaultBranch: String = "",
        val private: Boolean = false,
        @SerialName("pushed_at") val pushedAt: String? = null,
    ) {
        @Serializable
        data class Permissions(val push: Boolean = false)
    }

    @Serializable
    private data class Installations(val installations: List<Installation>)

    @Serializable
    private data class Repositories(val repositories: List<Repository>)

    @Serializable
    private data class AppLink(@SerialName("html_url") val htmlUrl: String)

    @Serializable
    private data class Commit(val sha: String, val commit: Details) {
        @Serializable
        data class Details(val message: String? = null)
    }
}
