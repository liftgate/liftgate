package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.OrgRole
import dev.liftgate.notify.Notification
import dev.liftgate.notify.NotificationChannel.Event
import dev.liftgate.notify.NotificationChannel.Kind
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

private const val TESTS_PER_MINUTE = 10

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
private data class ChannelRequest(val name: String, val events: Set<Event>, val kind: Kind? = null, val url: String? = null) {
    fun channelName() = name.trim().takeIf { it.length in 1..100 } ?: invalid("name must be 1 to 100 characters")

    fun channelEvents() = events.ifEmpty { invalid("choose at least one event") }
}

fun Route.notificationRoutes(app: App) {
    route("/orgs/{slug}/notifications") {
        get { call.respond(app.notificationChannels.list(call.org(app, OrgRole.ADMIN).id)) }
        post {
            val org = call.org(app, OrgRole.ADMIN)
            val body = call.receive<ChannelRequest>()
            val name = body.channelName()
            val events = body.channelEvents()
            val kind = body.kind ?: invalid("kind is required")
            call.respond(HttpStatusCode.Created, app.notificationChannels.create(org.id, name, kind, app.notifier.check(body.url ?: invalid("url is required")), events))
        }
        route("/{id}") {
            patch {
                val org = call.org(app, OrgRole.ADMIN)
                val body = call.receive<ChannelRequest>()
                call.respond(app.notificationChannels.update(org.id, call.uuid("id"), body.channelName(), body.channelEvents()))
            }
            delete {
                app.notificationChannels.delete(call.org(app, OrgRole.ADMIN).id, call.uuid("id"))
                call.respond(HttpStatusCode.NoContent)
            }
            post("/test") {
                val org = call.org(app, OrgRole.ADMIN)
                call.limit(app, "notification-test", TESTS_PER_MINUTE, org.id.toString())
                val endpoint = app.notificationChannels.endpoint(org.id, call.uuid("id")) ?: notFound("notification channel")
                val test = Notification("test", "Test notification from Liftgate for ${org.name}", "${app.config.dashboardUrl}/${org.slug}/settings/notifications", org.slug)
                app.notifier.send(endpoint, test)?.let(::invalid)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
