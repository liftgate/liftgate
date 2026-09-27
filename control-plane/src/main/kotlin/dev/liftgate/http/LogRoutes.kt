package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.events.buildLogSubject
import dev.liftgate.events.serviceLogSubject
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val LOG_SOCKETS_PER_USER = 20

fun Route.logRoutes(app: App) {
    val sockets = ConcurrentHashMap<UUID, Int>()
    route("/logs") {
        webSocket("/builds/{id}") { relay(app, sockets) { buildLogSubject(call.build(app).id) } }
        webSocket("/services/{id}") { relay(app, sockets) { serviceLogSubject(call.service(app).service.id) } }
    }
}

private suspend fun DefaultWebSocketServerSession.relay(app: App, sockets: ConcurrentHashMap<UUID, Int>, subject: suspend () -> String) {
    val name = try {
        subject()
    } catch (e: LiftgateException) {
        return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message))
    }
    val user = call.principal.user.id
    try {
        if (sockets.merge(user, 1, Int::plus)!! > LOG_SOCKETS_PER_USER) return close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many open log streams"))
        val relay = launch { app.nats.logs(name).collect { send(it) } }
        incoming.consumeEach { }
        relay.cancel()
    } finally {
        sockets.computeIfPresent(user) { _, open -> (open - 1).takeIf { it > 0 } }
    }
}
