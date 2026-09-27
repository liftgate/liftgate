package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.config.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Duration
import java.time.Instant

private val pollDeadline = Duration.ofMinutes(2)

fun Route.healthRoutes(app: App) {
    get("/healthz") {
        val deadline = Instant.now() - pollDeadline
        val stalled = app.nats.lastPolls.filterValues { it < deadline }.keys
        if (stalled.isEmpty()) call.respondText("ok")
        else call.respondText("not polling: ${stalled.sorted().joinToString()}", status = HttpStatusCode.ServiceUnavailable)
    }
    get("/readyz") {
        app.db.tx { exec("select 1") { it.next() } }
        if (!app.stopping && app.nats.connected && (!app.runs(Role.API) || app.cache.running)) call.respondText("ok")
        else call.respondText("not ready", status = HttpStatusCode.ServiceUnavailable)
    }
    get("/metrics") { call.respondText(app.metrics.scrape()) }
}
