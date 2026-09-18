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

fun Route.logRoutes(app: App) {
    route("/logs") {
        webSocket("/builds/{id}") { relay(app) { buildLogSubject(call.build(app).id) } }
        webSocket("/services/{id}") { relay(app) { serviceLogSubject(call.service(app).service.id) } }
    }
}

private suspend fun DefaultWebSocketServerSession.relay(app: App, subject: suspend () -> String) {
    val name = try {
        subject()
    } catch (e: LiftgateException) {
        return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message))
    }
    val relay = launch { app.nats.logs(name).collect { send(it) } }
    incoming.consumeEach { }
    relay.cancel()
}
