package dev.liftgate.metering

import dev.liftgate.http.json
import dev.liftgate.k8s.ORG_ID_LABEL
import dev.liftgate.k8s.SERVICE_ID_LABEL
import io.fabric8.kubernetes.api.model.PodBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * @author Dean
 * @date 9/17/2026
 */
class PrometheusTest {
    private val vector = """
        {"status":"success","data":{"resultType":"vector","result":[
          {"metric":{"namespace":"env-0123456789ab","pod":"api-5d9c-abcde"},"value":[1789000000.123,"12.5"]},
          {"metric":{"namespace":"env-0123456789ab","pod":"api-5d9c-fghij"},"value":[1789000000.123,"0.25"]}
        ]}}
    """

    private fun prometheus(status: HttpStatusCode, body: String, queries: MutableList<String?> = mutableListOf()) = Prometheus(
        "http://prometheus:9090",
        HttpClient(MockEngine { request ->
            queries += request.url.parameters["query"]
            assertEquals("/api/v1/query", request.url.encodedPath)
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) { install(ContentNegotiation) { json(json) } },
    )

    @Test
    fun `instant vectors become label sets mapped to values`() = runBlocking {
        val queries = mutableListOf<String?>()
        val result = prometheus(HttpStatusCode.OK, vector, queries).query("""sum by (namespace, pod) (rate(x{namespace=~"env-.+"}[5m]))""")
        assertEquals(
            mapOf(
                mapOf("namespace" to "env-0123456789ab", "pod" to "api-5d9c-abcde") to 12.5,
                mapOf("namespace" to "env-0123456789ab", "pod" to "api-5d9c-fghij") to 0.25,
            ),
            result,
        )
        assertEquals(listOf<String?>("""sum by (namespace, pod) (rate(x{namespace=~"env-.+"}[5m]))"""), queries)
    }

    @Test
    fun `empty results are an empty map`() = runBlocking {
        assertEquals(emptyMap(), prometheus(HttpStatusCode.OK, """{"status":"success","data":{"resultType":"vector","result":[]}}""").query("up"))
    }

    @Test
    fun `query errors are raised with the prometheus message`() {
        val failing = prometheus(HttpStatusCode.BadRequest, """{"status":"error","errorType":"bad_data","error":"parse error"}""")
        assertEquals("prometheus query failed: parse error", assertFailsWith<IllegalStateException> { runBlocking { failing.query("sum(") } }.message)
    }

    @Test
    fun `usage is summed per service and pods liftgate does not own are dropped`() {
        val owner = PodOwner(UUID.randomUUID(), UUID.randomUUID())
        val owners = mapOf(("env-a" to "api-1") to owner, ("env-a" to "api-2") to owner)
        val samples = mapOf(
            mapOf("namespace" to "env-a", "pod" to "api-1") to 1.5,
            mapOf("namespace" to "env-a", "pod" to "api-2") to 2.0,
            mapOf("namespace" to "env-b", "pod" to "api-1") to 9.0,
        )
        assertEquals(mapOf(owner to 3.5), attribute(samples, owners))
    }

    @Test
    fun `pod owner comes from the id labels`() {
        val owner = PodOwner(UUID.randomUUID(), UUID.randomUUID())
        val pod = PodBuilder().withNewMetadata().addToLabels(ORG_ID_LABEL, owner.orgId.toString()).addToLabels(SERVICE_ID_LABEL, owner.serviceId.toString()).endMetadata()
        assertEquals(owner, PodOwner.of(pod.build()))
        assertNull(PodOwner.of(pod.editMetadata().removeFromLabels(SERVICE_ID_LABEL).endMetadata().build()))
    }
}
