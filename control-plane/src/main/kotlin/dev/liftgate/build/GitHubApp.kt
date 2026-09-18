package dev.liftgate.build

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.liftgate.config.GitHubConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey
import java.time.Instant

private const val API = "https://api.github.com"

/**
 * @author Dean
 * @date 9/17/2026
 */
class GitHubApp(private val config: GitHubConfig, private val client: HttpClient) {
    private val key = when (val pem = PEMParser(StringReader(config.privateKeyPem.replace("\\n", "\n"))).use { it.readObject() }) {
        is PEMKeyPair -> JcaPEMKeyConverter().getPrivateKey(pem.privateKeyInfo)
        is PrivateKeyInfo -> JcaPEMKeyConverter().getPrivateKey(pem)
        else -> error("LIFTGATE_GITHUB_APP_PRIVATE_KEY must be a PEM encoded RSA private key")
    } as RSAPrivateKey

    fun appJwt(): String = Instant.now().let {
        JWT.create().withIssuer(config.appId).withIssuedAt(it.minusSeconds(60)).withExpiresAt(it.plusSeconds(540)).sign(Algorithm.RSA256(null, key))
    }

    suspend fun installationToken(installationId: Long): String =
        client.post("$API/app/installations/$installationId/access_tokens") { github(appJwt()) }.body<AccessToken>().token

    suspend fun branchHead(installationId: Long, repoFullName: String, branch: String): Pair<String, String?> =
        client.get("$API/repos/$repoFullName/commits/$branch") { github(installationToken(installationId)) }.body<Commit>().let { it.sha to it.commit.message }

    suspend fun installation(userToken: String, repoFullName: String): Long? = try {
        client.get("$API/repos/$repoFullName") { github(userToken) }.body<Repository>().permissions.push.takeIf { it }
            ?.let { client.get("$API/repos/$repoFullName/installation") { github(appJwt()) }.body<Installation>().id }
    } catch (e: ResponseException) {
        null
    }

    private fun HttpRequestBuilder.github(token: String) {
        expectSuccess = true
        bearerAuth(token)
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    @Serializable
    private data class AccessToken(val token: String)

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
