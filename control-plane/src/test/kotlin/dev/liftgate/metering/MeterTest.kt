package dev.liftgate.metering

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.db.Outbox
import dev.liftgate.db.UsageRecords
import dev.liftgate.events.Subject
import dev.liftgate.http.json
import dev.liftgate.k8s.MANAGED_LABEL
import dev.liftgate.k8s.ORG_ID_LABEL
import dev.liftgate.k8s.SERVICE_ID_LABEL
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * @author Dean
 * @date 9/27/2026
 */
@EnableKubernetesMockClient(crud = true)
class MeterTest {
    lateinit var client: KubernetesClient

    private val db = TestDatabase.clean()
    private val usage = """
        {"status":"success","data":{"resultType":"vector","result":[
          {"metric":{"namespace":"env-a","pod":"gone"},"value":[1789000000,"2"]},
          {"metric":{"namespace":"env-a","pod":"api"},"value":[1789000000,"3"]}
        ]}}
    """
    private val meter by lazy {
        val app = mockk<App> {
            every { this@mockk.db } returns this@MeterTest.db
            every { kube } returns client
        }
        val http = HttpClient(MockEngine { respond(usage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }) {
            install(ContentNegotiation) { json(json) }
        }
        Meter(app, Prometheus("http://prometheus:9090", http))
    }

    private fun pod(name: String, orgId: UUID, serviceId: UUID) = client.resource(
        PodBuilder().withNewMetadata().withName(name).withNamespace("env-a")
            .addToLabels(MANAGED_LABEL, "true").addToLabels(ORG_ID_LABEL, orgId.toString()).addToLabels(SERVICE_ID_LABEL, serviceId.toString())
            .endMetadata().build(),
    ).create()

    private suspend fun announced() = db.tx { Outbox.selectAll().where { Outbox.subject eq Subject.USAGE_RECORDED.value }.count() }

    @Test
    fun `a minute with no usage of a known service records and announces nothing, and one with usage announces it once`() = runBlocking {
        pod("gone", UUID.randomUUID(), UUID.randomUUID())
        meter.collectOnce(1.minutes)
        assertEquals(0L to 0L, db.tx { UsageRecords.selectAll().count() } to announced())

        val org = Orgs(db).create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id)
        val projects = Projects(db)
        val project = projects.create(org.id, "shop", "Shop", "acme/shop", 42)
        pod("api", org.id, Services(db).create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB)).id)
        meter.collectOnce(1.minutes)
        assertEquals(3L to 1L, db.tx { UsageRecords.selectAll().count() } to announced())
    }
}
