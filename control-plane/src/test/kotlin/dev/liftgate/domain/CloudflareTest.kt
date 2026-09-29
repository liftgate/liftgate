package dev.liftgate.domain

import dev.liftgate.TestDatabase
import dev.liftgate.config.CloudflareConfig
import dev.liftgate.db.Domains as DomainsTable
import dev.liftgate.db.now
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.json
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/27/2026
 */
class CloudflareTest {
    private val api = "/client/v4/zones/zone/custom_hostnames"
    private val requests = mutableListOf<HttpRequestData>()
    private var answer: (HttpRequestData) -> Pair<HttpStatusCode, String> = { error("unexpected ${it.method.value} ${it.url}") }
    private val cloudflare = Cloudflare(
        CloudflareConfig("zone", "token", "cname.liftgate.app"),
        HttpClient(MockEngine { request ->
            requests += request
            val (status, body) = answer(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) { install(ContentNegotiation) { json(json) } },
    )
    private val db by lazy { TestDatabase.clean() }

    private fun ok(result: String) = HttpStatusCode.OK to """{"success":true,"errors":[],"messages":[],"result":$result}"""

    private fun hostname(id: String, name: String, status: String = "pending", ssl: String = "pending_validation") =
        """{"id":"$id","hostname":"$name","status":"$status","ssl":{"status":"$ssl","validation_errors":[]},"verification_errors":[]}"""

    private val HttpRequestData.page get() = url.parameters["page"]

    private suspend fun service(): UUID {
        val org = Orgs(db).create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id)
        val projects = Projects(db)
        val project = projects.create(org.id, "shop", "Shop", "acme/shop", 42)
        val scope = requireNotNull(Services(db).scope(Services(db).create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB)).id))
        Domains(db, "liftgate.app").ensurePlatform(scope)
        return scope.service.id
    }

    @Test
    fun `create posts an http dv custom hostname with the api token`() = runBlocking {
        answer = { if (it.method == HttpMethod.Get) ok("[]") else ok(hostname("h1", "shop.example.com")) }
        assertEquals("h1", cloudflare.create("shop.example.com"))
        val post = requests.single { it.method == HttpMethod.Post }
        assertEquals(api, post.url.encodedPath)
        assertEquals("Bearer token", post.headers[HttpHeaders.Authorization])
        assertEquals(json.parseToJsonElement("""{"hostname":"shop.example.com","ssl":{"method":"http","type":"dv"}}"""), json.parseToJsonElement((post.body as TextContent).text))
    }

    @Test
    fun `create reuses the custom hostname an interrupted verification left behind`() = runBlocking {
        answer = { ok("[${hostname("h9", "shop.example.com")}]") }
        assertEquals("h9", cloudflare.create("shop.example.com"))
        assertTrue(requests.none { it.method == HttpMethod.Post })
    }

    @Test
    fun `the list is read page by page`() = runBlocking {
        answer = { request -> ok((1..if (request.page == "1") 50 else 1).joinToString(",", "[", "]") { hostname("p${request.page}-$it", "h$it.example.com") }) }
        assertEquals(51, cloudflare.hostnames().size)
        assertEquals(listOf("1" to "50", "2" to "50"), requests.map { it.page to it.url.parameters["per_page"] })
    }

    @Test
    fun `delete treats a missing hostname as deleted and fails closed on anything else`() = runBlocking {
        answer = { HttpStatusCode.NotFound to """{"success":false,"errors":[{"code":1436,"message":"The custom hostname was not found."}],"result":null}""" }
        cloudflare.delete("gone")
        assertEquals("$api/gone", requests.single().url.encodedPath)
        answer = { HttpStatusCode.Forbidden to """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}],"result":null}""" }
        val error = assertFailsWith<LiftgateException> { cloudflare.delete("h1") }
        assertEquals(HttpStatusCode.BadGateway to "edge_error", error.status to error.code)
        assertTrue("Authentication error" in error.message, error.message)
    }

    @Test
    fun `verification creates the custom hostname, records its id and points the cname at the edge, and deletion removes it`() = runBlocking {
        var token = ""
        val domains = Domains(db, "liftgate.app", edge = cloudflare, txt = { listOf(token) })
        val domain = domains.addCustom(service(), "shop.example.com")
        token = checkNotNull(domain.verificationToken)
        assertEquals(listOf(DnsRecord("TXT", "_liftgate.shop.example.com", token), DnsRecord("CNAME", "shop.example.com", "cname.liftgate.app")), domain.dnsRecords)
        answer = { if (it.method == HttpMethod.Get) ok("[]") else ok(hostname("h1", "shop.example.com")) }
        val verified = domains.verify(domain.id)
        assertNotNull(verified.verifiedAt)
        assertEquals(listOf(DnsRecord("CNAME", "shop.example.com", "cname.liftgate.app")), verified.dnsRecords)
        assertEquals("h1", domains.byId(domain.id)?.edgeId)
        answer = { ok("""{"id":"h1"}""") }
        domains.delete(domain.id)
        assertEquals(HttpMethod.Delete to "$api/h1", requests.last().method to requests.last().url.encodedPath)
        assertNull(domains.byId(domain.id))
    }

    @Test
    fun `a custom hostname Cloudflare refuses leaves the domain unverified`() = runBlocking {
        var token = ""
        val domains = Domains(db, "liftgate.app", edge = cloudflare, txt = { listOf(token) })
        val domain = domains.addCustom(service(), "shop.example.com")
        token = checkNotNull(domain.verificationToken)
        answer = { if (it.method == HttpMethod.Get) ok("[]") else HttpStatusCode.Forbidden to """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}""" }
        assertEquals(HttpStatusCode.BadGateway, assertFailsWith<LiftgateException> { domains.verify(domain.id) }.status)
        assertNull(domains.byId(domain.id)?.verifiedAt)
    }

    @Test
    fun `the poller records pending, active and failed custom hostnames with Cloudflare's message`() = runBlocking {
        val serviceId = service()
        val hosts = mapOf("pending.example.com" to "h1", "active.example.com" to "h2", "failed.example.com" to "h3", "lost.example.com" to "h4")
        db.tx {
            hosts.forEach { (host, edgeId) ->
                DomainsTable.insert {
                    it[DomainsTable.id] = UUID.randomUUID()
                    it[DomainsTable.serviceId] = serviceId
                    it[DomainsTable.hostname] = host
                    it[DomainsTable.kind] = "custom"
                    it[verificationToken] = "token"
                    it[verifiedAt] = now()
                    it[DomainsTable.edgeId] = edgeId
                }
            }
        }
        answer = {
            ok(
                listOf(
                    """{"id":"h1","hostname":"pending.example.com","status":"pending","ssl":{"status":"pending_validation"},"verification_errors":["custom hostname does not CNAME to this zone."]}""",
                    hostname("h2", "active.example.com", "active", "active"),
                    """{"id":"h3","hostname":"failed.example.com","status":"pending","ssl":{"status":"validation_timed_out","validation_errors":[{"message":"caa_error: blocked by CAA"}]}}""",
                ).joinToString(",", "[", "]"),
            )
        }
        Domains(db, "liftgate.app", edge = cloudflare).refreshEdge()
        val states = Domains(db, "liftgate.app").forService(serviceId).associate { it.hostname to CertificateState(it.certificateStatus, it.certificateMessage) }
        assertEquals(CertificateState("pending", "custom hostname does not CNAME to this zone."), states["pending.example.com"])
        assertEquals(CertificateState("ready"), states["active.example.com"])
        assertEquals(CertificateState("failed", "caa_error: blocked by CAA"), states["failed.example.com"])
        assertEquals(CertificateState("failed", "Cloudflare has no custom hostname for lost.example.com"), states["lost.example.com"])
    }
}
