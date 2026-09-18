package dev.liftgate.http

import dev.liftgate.App
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.healthRoutes(app: App) {
    get("/healthz") { call.respondText("ok") }
    get("/readyz") {
        app.db.tx { exec("select 1") { it.next() } }
        call.respondText("ok")
    }
    get("/metrics") { call.respondText(app.metrics.scrape()) }
}
