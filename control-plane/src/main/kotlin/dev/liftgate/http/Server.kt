package dev.liftgate.http

import dev.liftgate.App
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.hostWithPortIfSpecified
import io.ktor.http.protocolWithAuthority
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.SerializationException
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory

private const val UNIQUE_VIOLATION = "23505"
private val log = LoggerFactory.getLogger("dev.liftgate.http")

fun Application.liftgate(app: App) {
    install(ContentNegotiation) { json(json) }
    install(CallLogging)
    install(MicrometerMetrics) { registry = app.metrics }
    install(WebSockets)
    val dashboard = Url(app.config.dashboardUrl)
    if (dashboard.protocolWithAuthority != Url(app.config.publicUrl).protocolWithAuthority) install(CORS) {
        allowHost(dashboard.hostWithPortIfSpecified, listOf(dashboard.protocol.name))
        allowCredentials = true
        allowHeader(HttpHeaders.ContentType)
        listOf(HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete).forEach(::allowMethod)
    }
    install(StatusPages) {
        exception<LiftgateException> { call, e -> call.respond(e.status, ErrorBody(e.code, e.message)) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", e.cause?.message ?: e.message ?: "bad request")) }
        exception<SerializationException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", e.message ?: "malformed body")) }
        exception<ExposedSQLException> { call, e ->
            if (e.sqlState == UNIQUE_VIOLATION) call.respond(HttpStatusCode.Conflict, ErrorBody("conflict", "already exists")) else internalError(call, e)
        }
        exception<Throwable> { call, e -> internalError(call, e) }
    }
    install(authPlugin(app))
    routing { apiRoutes(app) }
}

fun httpServer(app: App) = embeddedServer(Netty, port = app.config.httpPort, host = "0.0.0.0") { liftgate(app) }

private suspend fun internalError(call: ApplicationCall, e: Throwable) {
    log.error("unhandled error on {} {}", call.request.httpMethod.value, call.request.uri, e)
    call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal_error", "internal error"))
}
