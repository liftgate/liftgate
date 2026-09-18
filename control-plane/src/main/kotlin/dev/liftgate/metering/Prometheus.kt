package dev.liftgate.metering

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * @author Dean
 * @date 9/17/2026
 */
class Prometheus(private val baseUrl: String, private val client: HttpClient) {
    suspend fun query(promql: String): Map<Map<String, String>, Double> {
        val response = client.get("$baseUrl/api/v1/query") { parameter("query", promql) }.body<Response>()
        check(response.status == "success") { "prometheus query failed: ${response.error}" }
        return response.data?.result.orEmpty().associate { it.metric to it.value.jsonArray[1].jsonPrimitive.content.toDouble() }
    }

    @Serializable
    private data class Response(val status: String, val error: String? = null, val data: Data? = null)

    @Serializable
    private data class Data(val result: List<Sample>)

    @Serializable
    private data class Sample(val metric: Map<String, String>, val value: JsonElement)
}
