package dev.liftgate.build

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.liftgate.config.GitHubConfig
import dev.liftgate.project.Project
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
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

private const val API = "https://api.github.com"
private const val GRAPHQL = "$API/graphql"
private const val PER_PAGE = 100
private const val MAX_BLOB_BYTES = 64 * 1024

val ClientRequestException.rateLimited
    get() = response.status == HttpStatusCode.TooManyRequests || response.headers["x-ratelimit-remaining"] == "0" || response.headers[HttpHeaders.RetryAfter] != null

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
        commit(installationToken(installationId, repoFullName.substringAfter('/')), repoFullName, branch).let { it.sha to it.message }

    suspend fun commit(installationToken: String, repoFullName: String, ref: String): Head =
        client.get("$API/repos/$repoFullName/commits/$ref") { github(installationToken) }.body<Commit>().let { Head(it.sha, it.commit.message, it.commit.tree?.sha ?: it.sha) }

    suspend fun tree(installationToken: String, repoFullName: String, treeSha: String): Pair<List<String>, Boolean> =
        client.get("$API/repos/$repoFullName/git/trees/$treeSha") { github(installationToken); parameter("recursive", 1) }.body<Tree>()
            .let { tree -> tree.tree.filter { it.type == "blob" }.map { it.path } to tree.truncated }

    suspend fun files(installationToken: String, repoFullName: String, sha: String, paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val aliases = paths.indices.map { "p$it" }
        val query = "query(\$owner: String!, \$name: String!, ${aliases.joinToString { "\$$it: String!" }}) " +
            "{ repository(owner: \$owner, name: \$name) { ${aliases.joinToString(" ") { "$it: object(expression: \$$it) { ... on Blob { text byteSize isBinary } }" }} } }"
        val variables = buildJsonObject {
            put("owner", repoFullName.substringBefore('/'))
            put("name", repoFullName.substringAfter('/'))
            paths.forEachIndexed { i, path -> put(aliases[i], "$sha:$path") }
        }
        val blobs = client.post(GRAPHQL) {
            github(installationToken)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("query", query); put("variables", variables) })
        }.body<Blobs>().takeIf { it.errors == null }?.data?.repository ?: error("the GraphQL file query returned no repository")
        return paths.withIndex().mapNotNull { (i, path) -> blobs[aliases[i]]?.takeIf { !it.isBinary && it.byteSize <= MAX_BLOB_BYTES }?.text?.let { path to it } }.toMap()
    }

    suspend fun canPush(installationToken: String, repoFullName: String, login: String): Boolean = try {
        client.get("$API/repos/$repoFullName/collaborators/$login/permission") { github(installationToken) }.body<CollaboratorPermission>().permission in setOf("admin", "write")
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound) false else throw e
    }

    suspend fun <T> asImporter(project: Project, permission: Pair<String, String>, block: suspend (String) -> T): T? {
        val token = installationToken(project.installationId, project.repoFullName.substringAfter('/'), mapOf(permission, "metadata" to "read"))
        return if (project.importedByLogin?.let { canPush(token, project.repoFullName, it) } == false) null else block(token)
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

    suspend fun installationSettings(installationId: Long): Installation = client.get("$API/app/installations/$installationId") { github(appJwt()) }.body()

    suspend fun comment(installationToken: String, repoFullName: String, number: Int, commentId: Long?, body: String): Long {
        val request: HttpRequestBuilder.() -> Unit = {
            github(installationToken)
            contentType(ContentType.Application.Json)
            setBody(mapOf("body" to body))
        }
        val response = if (commentId == null) client.post("$API/repos/$repoFullName/issues/$number/comments", request) else client.patch("$API/repos/$repoFullName/issues/comments/$commentId", request)
        return response.body<Comment>().id
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
    data class Installation(val id: Long, val permissions: Map<String, String> = emptyMap(), val events: List<String> = emptyList())

    @Serializable
    private data class Comment(val id: Long)

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
        data class Details(val message: String? = null, val tree: Ref? = null)

        @Serializable
        data class Ref(val sha: String)
    }

    data class Head(val sha: String, val message: String?, val treeSha: String)

    @Serializable
    private data class Tree(val tree: List<Entry>, val truncated: Boolean = false) {
        @Serializable
        data class Entry(val path: String, val type: String)
    }

    @Serializable
    private data class Blobs(val data: Data? = null, val errors: JsonArray? = null) {
        @Serializable
        data class Data(val repository: Map<String, Blob?>? = null)

        @Serializable
        data class Blob(val text: String? = null, val byteSize: Int = 0, val isBinary: Boolean = false)
    }
}
