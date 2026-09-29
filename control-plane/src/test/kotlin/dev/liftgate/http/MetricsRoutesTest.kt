package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.auth.Access
import dev.liftgate.auth.Sessions
import dev.liftgate.metering.Prometheus
import dev.liftgate.metering.Prometheus.Point
import dev.liftgate.metering.ServiceMetrics
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/27/2026
 */
@EnableKubernetesMockClient
class MetricsRoutesTest {
    lateinit var server: KubernetesMockServer
    lateinit var client: KubernetesClient

    private val db = TestDatabase.clean()
    private val orgs = Orgs(db)
    private val projects = Projects(db)
    private val services = Services(db)
    private val member = runBlocking { db.tx { insertUser("dean", null, null, null) } }
    private val rival = runBlocking { db.tx { insertUser("eve", null, null, null) } }
    private val queries = ConcurrentLinkedQueue<String>()
    private val prometheus = Prometheus(
        "http://prometheus:9090",
        HttpClient(MockEngine { request ->
            val query = request.url.parameters["query"].orEmpty().also(queries::add)
            val value = listOf("cpu" to "0.25", "memory" to "104857600", "receive" to "2048", "transmit" to "512").first { (metric, _) -> metric in query }.second
            val body = """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":[[1789000000,"$value"],[1789000012,"$value"]]}]}}"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) { install(ContentNegotiation) { json(json) } },
    )

    private fun app() = mockk<App>().also {
        every { it.services } returns services
        every { it.access } returns Access(orgs)
        every { it.sessions } returns mockk<Sessions> {
            coEvery { resolve("member") } returns member
            coEvery { resolve("rival") } returns rival
        }
        every { it.metrics } returns PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        every { it.config } returns testConfig()
        every { it.prometheus } returns prometheus
        every { it.kube } returns client
    }

    private suspend fun seed(forbidden: Boolean = false): Pair<Service, String> {
        val acme = orgs.create("acme", "Acme", member.id)
        orgs.create("rival", "Rival", rival.id)
        val environment = projects.environments(projects.create(acme.id, "shop", "Shop", "acme/shop", 42).id).single()
        val service = services.create(environment.id, ServiceSpec("web", "Web", ServiceKind.WEB, memoryMb = 256))
        val pod = { name: String, restarts: Int ->
            PodBuilder().withNewMetadata().withName(name).endMetadata()
                .withNewStatus().addNewContainerStatus().withName("app").withRestartCount(restarts).endContainerStatus().endStatus().build()
        }
        val listing = server.expect().get().withPath("/api/v1/namespaces/${environment.namespace}/pods?labelSelector=liftgate.dev%2Fservice-id%3D${service.id}")
        if (forbidden) listing.andReturn(403, StatusBuilder().withCode(403).withReason("Forbidden").build()).always()
        else listing.andReturn(200, PodListBuilder().withItems(pod("web-7d9f-aaaaa", 2), pod("web-7d9f-bbbbb", 1)).build()).always()
        return service to environment.namespace
    }

    @Test
    fun `members get pod-level cpu, memory against its limit, network and restarts of their service`() = testApplication {
        val (service, namespace) = seed()
        application { liftgate(app()) }
        val response = client.get("/api/v1/services/${service.id}/metrics") { cookie(SESSION_COOKIE, "member") }
        assertEquals(HttpStatusCode.OK, response.status)
        val metrics = json.decodeFromString(ServiceMetrics.serializer(), response.bodyAsText())
        val points = { value: Double -> listOf(Point(1789000000, value), Point(1789000012, value)) }
        assertEquals(listOf(points(0.25), points(104857600.0), points(2048.0), points(512.0)), listOf(metrics.cpu, metrics.memory, metrics.networkRx, metrics.networkTx))
        assertEquals(256L * 1024 * 1024 to 3, metrics.memoryLimitBytes to metrics.restarts)
        assertEquals(12L to 300L, metrics.step to (metrics.end - metrics.start) / metrics.step + 1)
        val report = queries.joinToString("\n")
        assertEquals(4, queries.size, report)
        assertTrue(queries.all { "kube_pod_labels{namespace=\"$namespace\", label_liftgate_dev_service_id=\"${service.id}\"}" in it && "container!=" !in it }, report)
        assertTrue(queries.filter { "cpu" in it || "memory" in it }.all { "container=\"\", pod!=\"\"" in it }, report)
        assertTrue(queries.filterNot { "memory" in it }.all { "[300s]" in it }, report)
    }

    @Test
    fun `the range picks the step and rate window, and unknown ranges are rejected before prometheus is asked`() = testApplication {
        val (service) = seed()
        application { liftgate(app()) }
        val week = client.get("/api/v1/services/${service.id}/metrics?range=7d") { cookie(SESSION_COOKIE, "member") }
        assertEquals(2016L, json.decodeFromString(ServiceMetrics.serializer(), week.bodyAsText()).step)
        assertTrue(queries.first { "cpu" in it }.contains("[2016s]"))
        queries.clear()
        val month = client.get("/api/v1/services/${service.id}/metrics?range=30d") { cookie(SESSION_COOKIE, "member") }
        assertEquals(HttpStatusCode.UnprocessableEntity, month.status)
        assertEquals(emptyList(), queries.toList())
    }

    @Test
    fun `restarts are 0 while the api may not yet read pods in a namespace that was never released`() = testApplication {
        val (service) = seed(forbidden = true)
        application { liftgate(app()) }
        val response = client.get("/api/v1/services/${service.id}/metrics") { cookie(SESSION_COOKIE, "member") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, json.decodeFromString(ServiceMetrics.serializer(), response.bodyAsText()).restarts)
    }

    @Test
    fun `a member of another org is forbidden`() = testApplication {
        val (service) = seed()
        application { liftgate(app()) }
        listOf("", "?range=30d").forEach {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/services/${service.id}/metrics$it") { cookie(SESSION_COOKIE, "rival") }.status)
        }
        assertEquals(emptyList(), queries.toList())
    }
}
