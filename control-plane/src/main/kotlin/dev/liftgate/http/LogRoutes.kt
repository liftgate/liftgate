package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.deploy.BuildStatus
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val LOG_SOCKETS_PER_USER = 20

fun Route.logRoutes(app: App) {
    val sockets = ConcurrentHashMap<UUID, Int>()
    route("/logs") {
        webSocket("/builds/{id}") {
            relay(sockets) {
                val id = call.build(app).id
                app.nats.logs.follow(id) { app.builds.byId(id)?.status.let { it != BuildStatus.QUEUED && it != BuildStatus.RUNNING } }
            }
        }
        webSocket("/services/{id}") {
            relay(sockets) {
                val scope = call.service(app)
                app.podLogs.follow(scope.environment.namespace, scope.service.id, call.request.queryParameters["previous"] == "true")
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.relay(sockets: ConcurrentHashMap<UUID, Int>, lines: suspend () -> Flow<String>) {
    val flow = try {
        lines()
    } catch (e: LiftgateException) {
        return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.message))
    }
    val user = call.principal.user.id
    try {
        if (sockets.merge(user, 1, Int::plus)!! > LOG_SOCKETS_PER_USER) return close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many open log streams"))
        val relay = launch {
            flow.collect { send(it) }
            close(CloseReason(CloseReason.Codes.NORMAL, "end of log"))
        }
        incoming.consumeEach { }
        relay.cancel()
    } finally {
        sockets.computeIfPresent(user) { _, open -> (open - 1).takeIf { it > 0 } }
    }
}
