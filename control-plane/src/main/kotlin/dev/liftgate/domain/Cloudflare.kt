@file:UseSerializers(InstantSerializer::class)

package dev.liftgate.domain

import dev.liftgate.config.CloudflareConfig
import dev.liftgate.http.InstantSerializer
import dev.liftgate.http.LiftgateException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

private const val API = "https://api.cloudflare.com/client/v4"
private const val PAGE_SIZE = 50
private val serving = setOf("active", "active_redeploying")
private val lost = setOf("moved", "blocked", "pending_blocked", "deleted", "pending_deletion")

/**
 * @author Dean
 * @date 9/27/2026
 */
class Cloudflare(private val config: CloudflareConfig, private val client: HttpClient) {
    val cnameTarget get() = config.cnameTarget

    suspend fun create(hostname: String): String = hostnames().firstOrNull { it.hostname == hostname }?.id
        ?: read<Hostname>(
            send(HttpMethod.Post) {
                setBody(buildJsonObject {
                    put("hostname", hostname)
                    putJsonObject("ssl") { put("method", "http"); put("type", "dv") }
                })
            },
        ).id

    suspend fun hostnames(): List<Hostname> {
        val hostnames = mutableListOf<Hostname>()
        do {
            val page = read<List<Hostname>>(send(HttpMethod.Get) { parameter("page", hostnames.size / PAGE_SIZE + 1); parameter("per_page", PAGE_SIZE) })
            hostnames += page
        } while (page.size == PAGE_SIZE)
        return hostnames
    }

    suspend fun delete(id: String) {
        val response = send(HttpMethod.Delete, "/$id")
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NotFound) read<JsonElement>(response)
    }

    private suspend fun send(method: HttpMethod, path: String = "", block: HttpRequestBuilder.() -> Unit = {}) =
        client.request("$API/zones/${config.zoneId}/custom_hostnames$path") {
            this.method = method
            bearerAuth(config.apiToken)
            contentType(ContentType.Application.Json)
            block()
        }

    private suspend inline fun <reified T> read(response: HttpResponse): T {
        val envelope = runCatching { response.body<Envelope<T>>() }.getOrNull()
        return envelope?.result?.takeIf { response.status.isSuccess() && envelope.success }
            ?: throw LiftgateException(HttpStatusCode.BadGateway, "edge_error", "Cloudflare answered ${response.status.value}" + envelope?.errors?.joinToString("; ", ": ") { it.message }.orEmpty())
    }

    @Serializable
    data class Envelope<T>(val success: Boolean = false, val errors: List<Message> = emptyList(), val result: T? = null)

    @Serializable
    data class Message(val message: String)

    @Serializable
    data class Ssl(val status: String = "", @SerialName("validation_errors") val validationErrors: List<Message> = emptyList())

    @Serializable
    data class Hostname(
        val id: String,
        val hostname: String,
        val status: String = "",
        val ssl: Ssl? = null,
        @SerialName("verification_errors") val verificationErrors: List<String> = emptyList(),
        @SerialName("created_at") val createdAt: Instant? = null,
    ) {
        val certificate: CertificateState
            get() {
                val ssl = ssl?.status.orEmpty()
                val message = (verificationErrors + this.ssl?.validationErrors.orEmpty().map { it.message }).joinToString("; ").ifEmpty { null }
                return when {
                    status in serving && ssl == "active" -> CertificateState("ready")
                    status in lost || ssl == "expired" || ssl.endsWith("timed_out") -> CertificateState("failed", message ?: "the custom hostname is $status and its certificate $ssl")
                    else -> CertificateState("pending", message)
                }
            }
    }
}
