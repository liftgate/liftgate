package dev.liftgate.notify

import dev.liftgate.App
import dev.liftgate.TestDatabase
import dev.liftgate.db.NotificationChannels as ChannelsTable
import dev.liftgate.db.Outbox
import dev.liftgate.deploy.Builds
import dev.liftgate.events.Subject
import dev.liftgate.events.uuid
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.json
import dev.liftgate.notify.NotificationChannel.Event
import dev.liftgate.notify.NotificationChannel.Kind
import dev.liftgate.notify.NotificationChannels.Endpoint
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.secret.SecretBox
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.testConfig
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
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
class NotificationChannelsTest {
    private val db = TestDatabase.clean()
    private val channels = NotificationChannels(db, SecretBox(ByteArray(32)))
    private val hook = "https://hooks.slack.com/services/T000/B000/xyz"

    private suspend fun org(slug: String) = Orgs(db).create(slug, slug, db.tx { insertUser(slug, null, null, null) }.id)

    @Test
    fun `urls are sealed, the list shows only their host, and only a webhook gets a signing secret, returned once`() = runBlocking {
        val org = org("acme")
        val slack = channels.create(org.id, "Deploys", Kind.SLACK, hook, setOf(Event.DEPLOYMENT_RUNNING))
        val webhook = channels.create(org.id, "Ops", Kind.WEBHOOK, "https://ops.example.com/liftgate", setOf(Event.BUILD_FAILED, Event.DEPLOYMENT_FAILED))
        assertNull(slack.secret)
        val secret = assertNotNull(webhook.secret)
        assertEquals(listOf("hooks.slack.com" to null, "ops.example.com" to null), channels.list(org.id).map { it.host to it.secret })
        val stored = db.tx { ChannelsTable.select(ChannelsTable.urlEncrypted).map { String(it[ChannelsTable.urlEncrypted], Charsets.ISO_8859_1) } }
        assertTrue(stored.none { "example.com" in it || "slack.com" in it })
        assertEquals(Endpoint(Kind.WEBHOOK, "https://ops.example.com/liftgate", secret), channels.endpoint(org.id, webhook.id))
    }

    @Test
    fun `channels belong to one organization, which can hold at most ten`() = runBlocking {
        val acme = org("acme")
        val rival = org("rival")
        val channel = channels.create(acme.id, "Deploys", Kind.SLACK, hook, setOf(Event.DEPLOYMENT_RUNNING))
        assertNull(channels.endpoint(rival.id, channel.id))
        assertFailsWith<LiftgateException> { channels.update(rival.id, channel.id, "Mine", setOf(Event.BUILD_FAILED)) }
        assertFailsWith<LiftgateException> { channels.delete(rival.id, channel.id) }
        assertEquals("Failures" to setOf(Event.BUILD_FAILED), channels.update(acme.id, channel.id, "Failures", setOf(Event.BUILD_FAILED)).let { it.name to it.events })
        repeat(9) { channels.create(acme.id, "Channel $it", Kind.DISCORD, hook, setOf(Event.BUILD_FAILED)) }
        val full = assertFailsWith<LiftgateException> { channels.create(acme.id, "One more", Kind.SLACK, hook, setOf(Event.BUILD_FAILED)) }
        assertEquals(HttpStatusCode.Conflict, full.status)
        channels.create(rival.id, "Theirs", Kind.SLACK, hook, setOf(Event.BUILD_FAILED))
        channels.delete(acme.id, channel.id)
        assertEquals(9, channels.list(acme.id).size)
    }

    @Test
    fun `a failed build is queued once for each channel of its organization that subscribed to build failures`() = runBlocking {
        val org = org("acme")
        val projects = Projects(db)
        val services = Services(db)
        val builds = Builds(db)
        val project = projects.create(org.id, "shop", "Shop", "acme/shop", 42)
        val service = services.create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
        val build = builds.request(service.id, "abc1234def", null, "main").also { builds.markFailed(it.id, "the build job failed") }
        val failures = channels.create(org.id, "Failures", Kind.SLACK, hook, setOf(Event.BUILD_FAILED, Event.DEPLOYMENT_FAILED))
        channels.create(org.id, "Deploys", Kind.DISCORD, hook, setOf(Event.DEPLOYMENT_RUNNING))
        channels.create(org("rival").id, "Theirs", Kind.SLACK, hook, setOf(Event.BUILD_FAILED))
        val app = mockk<App> {
            every { config } returns testConfig()
            every { db } returns this@NotificationChannelsTest.db
            every { this@mockk.builds } returns builds
            every { this@mockk.services } returns services
            every { notificationChannels } returns channels
        }
        Notifier(app).fanOut(Event.BUILD_FAILED, build.id, null)
        val payload = db.tx { Outbox.selectAll().where { Outbox.subject eq Subject.NOTIFICATION_REQUESTED.value }.map { it[Outbox.payload] } }.single()
        assertEquals(org.id to failures.id, payload.uuid("orgId") to payload.uuid("channelId"))
        val notification = json.decodeFromJsonElement<Notification>(payload.getValue("notification"))
        assertEquals("Build of shop/api in production at abc1234 failed: the build job failed", notification.text)
        assertEquals("http://localhost:3000/acme/shop/production/api?tab=builds&build=${build.id}", notification.url)
    }
}
