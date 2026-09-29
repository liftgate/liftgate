package dev.liftgate.metering

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
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
    suspend fun query(promql: String): Map<Map<String, String>, Double> =
        fetch("query") { parameter("query", promql) }.associate { it.metric to checkNotNull(it.value).point.value }

    suspend fun range(promql: String, start: Long, end: Long, step: Long): Map<Map<String, String>, List<Point>> =
        fetch("query_range") {
            parameter("query", promql)
            parameter("start", start)
            parameter("end", end)
            parameter("step", step)
        }.associate { sample -> sample.metric to sample.values.map { it.point } }

    private suspend fun fetch(path: String, parameters: HttpRequestBuilder.() -> Unit): List<Sample> {
        val response = client.get("$baseUrl/api/v1/$path", parameters).body<Response>()
        check(response.status == "success") { "prometheus query failed: ${response.error}" }
        return response.data?.result.orEmpty()
    }

    private val JsonElement.point get() = jsonArray.map { it.jsonPrimitive.content.toDouble() }.let { (time, value) -> Point(time.toLong(), value) }

    @Serializable
    data class Point(val time: Long, val value: Double)

    @Serializable
    private data class Response(val status: String, val error: String? = null, val data: Data? = null)

    @Serializable
    private data class Data(val result: List<Sample>)

    @Serializable
    private data class Sample(val metric: Map<String, String>, val value: JsonElement? = null, val values: List<JsonElement> = emptyList())
}
