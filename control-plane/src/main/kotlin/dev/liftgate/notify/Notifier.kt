package dev.liftgate.notify

import dev.liftgate.App
import dev.liftgate.build.Webhooks
import dev.liftgate.db.sql
import dev.liftgate.deploy.Build
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Deployment
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.events.Redeliver
import dev.liftgate.events.Subject
import dev.liftgate.events.changed
import dev.liftgate.events.enqueue
import dev.liftgate.events.uuid
import dev.liftgate.http.invalid
import dev.liftgate.http.json
import dev.liftgate.k8s.Resources
import dev.liftgate.notify.NotificationChannel.Event
import dev.liftgate.notify.NotificationChannel.Kind
import dev.liftgate.notify.NotificationChannels.Endpoint
import dev.liftgate.service.ServiceScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.parseUrl
import io.netty.handler.ipfilter.IpFilterRuleType
import io.netty.handler.ipfilter.IpSubnetFilterRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val SIGNATURE = "X-Liftgate-Signature"
private const val DELIVERIES = 8
private const val MAX_URL = 2048
private const val MAX_ERROR = 300
private val unroutable = listOf("0.0.0.0/8", "127.0.0.0/8", "192.0.0.0/24", "192.0.2.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3")
private val minRetry = Duration.ofSeconds(10)
private val maxRetry = Duration.ofMinutes(10)
private val giveUp = Duration.ofHours(1)

/**
 * @author Dean
 * @date 9/27/2026
 */
class Notifier(
    private val app: App,
    private val lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    engine: HttpClientEngine? = null,
) {
    private val log = LoggerFactory.getLogger(Notifier::class.java)
    private val blocked = (Resources.privateRanges + unroutable).map { IpSubnetFilterRule(it, IpFilterRuleType.REJECT) } + app.config.notificationDeniedCidrs
    private val client = HttpClient(engine ?: CIO.create { dnsResolver = ::resolve }) {
        followRedirects = false
        install(UserAgent) { agent = "liftgate" }
    }

    fun start() {
        app.nats.consume(Subject.BUILD_COMPLETED, "api-notify-build-completed", app.scope) {
            if (it.getValue("status").jsonPrimitive.content == BuildStatus.FAILED.sql) fanOut(Event.BUILD_FAILED, it.uuid("buildId"), null)
        }
        app.nats.consume(Subject.DEPLOYMENT_UPDATED, "api-notify-deployment-updated", app.scope) {
            val event = when (it.getValue("status").jsonPrimitive.content) {
                DeploymentStatus.RUNNING.sql -> Event.DEPLOYMENT_RUNNING
                DeploymentStatus.FAILED.sql -> Event.DEPLOYMENT_FAILED
                else -> null
            }
            if (event != null && it.changed) app.deployments.byId(it.uuid("deploymentId"))?.let { deployment -> fanOut(event, deployment.buildId, deployment) }
        }
        app.nats.consume(Subject.NOTIFICATION_REQUESTED, "api-notification-requested", app.scope, concurrency = DELIVERIES) { deliver(it) }
    }

    suspend fun fanOut(event: Event, buildId: UUID, deployment: Deployment?) {
        val build = app.builds.byId(buildId) ?: return
        val scope = app.services.scope(build.serviceId) ?: return
        val channels = app.notificationChannels.subscribers(scope.org.id, event).ifEmpty { return }
        val notification = json.encodeToJsonElement(notification(event, build, deployment, scope))
        app.db.tx {
            channels.forEach {
                enqueue(Subject.NOTIFICATION_REQUESTED, buildJsonObject { put("orgId", scope.org.id.toString()); put("channelId", it.toString()); put("notification", notification) })
            }
        }
    }

    suspend fun deliver(payload: JsonObject) {
        val notification = json.decodeFromJsonElement<Notification>(payload.getValue("notification"))
        val channelId = payload.uuid("channelId")
        val endpoint = app.notificationChannels.endpoint(payload.uuid("orgId"), channelId) ?: return
        val age = Duration.between(notification.at, Instant.now())
        if (age > giveUp) return log.warn("dropped a {} notification for channel {} that is more than an hour old", notification.event, channelId)
        val failure = send(endpoint, notification) ?: return
        log.info("notification channel {} failed: {}", channelId, failure)
        throw Redeliver(age.coerceIn(minRetry, maxRetry))
    }

    suspend fun send(endpoint: Endpoint, notification: Notification): String? {
        val body = when (endpoint.kind) {
            Kind.SLACK -> buildJsonObject { put("text", "${notification.text}\n${notification.url}") }
            Kind.DISCORD -> buildJsonObject { put("content", "${notification.text}\n${notification.url}") }
            Kind.WEBHOOK -> json.encodeToJsonElement(notification)
        }.toString()
        return try {
            client.preparePost(endpoint.url) {
                setBody(TextContent(body, ContentType.Application.Json))
                endpoint.secret?.let { header(SIGNATURE, Webhooks.sign(it, body.toByteArray())) }
            }.execute { it.status }.takeUnless { it.isSuccess() }?.let { "the endpoint answered $it" }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            "the endpoint could not be reached: ${(e as? UnknownHostException)?.message ?: e.javaClass.simpleName}"
        }
    }

    suspend fun check(url: String): String {
        val host = parseUrl(url)?.takeIf { url.length <= MAX_URL && it.protocol == URLProtocol.HTTPS }?.host ?: invalid("url must be an https URL")
        try {
            resolve(host)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            invalid("$host must resolve to a public IPv4 address")
        }
        return url
    }

    private suspend fun resolve(host: String): List<String> = runInterruptible(Dispatchers.IO) { lookup(host) }
        .filter { it is Inet4Address && blocked.none { rule -> rule.matches(InetSocketAddress(it, 0)) } }
        .map { it.hostAddress }
        .ifEmpty { throw UnknownHostException("$host has no public IPv4 address") }

    private fun notification(event: Event, build: Build, deployment: Deployment?, scope: ServiceScope): Notification {
        val subject = "${scope.project.slug}/${scope.service.slug} in ${scope.environment.slug} at ${build.commitSha.take(7)}"
        val error = (deployment?.error ?: build.error)?.let { ": ${it.take(MAX_ERROR)}" }.orEmpty()
        val text = when (event) {
            Event.BUILD_FAILED -> "Build of $subject failed$error"
            Event.DEPLOYMENT_RUNNING -> "Deployed $subject"
            Event.DEPLOYMENT_FAILED -> "Deployment of $subject failed$error"
        }
        return Notification(
            event.sql, text, scope.buildUrl(app.config.dashboardUrl, build.id), scope.org.slug,
            scope.project.slug, scope.environment.slug, scope.service.slug, build.commitSha, build.id, deployment?.id,
        )
    }
}
