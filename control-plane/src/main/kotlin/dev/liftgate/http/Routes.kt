package dev.liftgate.http

import dev.liftgate.App
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import java.util.UUID

fun Route.apiRoutes(app: App) {
    healthRoutes(app)
    route("/api/v1") {
        authRoutes(app)
        passkeyRoutes(app)
        emailRoutes(app)
        ssoRoutes(app)
        orgRoutes(app)
        projectRoutes(app)
        serviceRoutes(app)
        deployRoutes(app)
        domainRoutes(app)
        logRoutes(app)
        webhookRoutes(app)
    }
}

fun ApplicationCall.uuid(name: String): UUID = parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    ?: throw LiftgateException(HttpStatusCode.BadRequest, "bad_request", "$name must be a UUID")

fun ApplicationCall.clientIp(trustedProxies: Int): String =
    request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty().flatMap { it.split(',') }.map(String::trim)
        .let { hops -> hops.getOrNull(hops.size - trustedProxies) }?.takeIf { it.isNotEmpty() } ?: request.origin.remoteAddress
