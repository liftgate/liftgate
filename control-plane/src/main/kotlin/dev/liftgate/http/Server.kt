package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.config.Role
import io.ktor.http.ContentType
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
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentTypeWithQuality
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.SerializationException
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory
import java.util.UUID

private const val UNIQUE_VIOLATION = "23505"
private const val BODY_LIMIT = 1024L * 1024
const val WEBSOCKET_FRAME_LIMIT = 64L * 1024
private val tooLarge = ErrorBody("payload_too_large", "the request body is too large")
private val log = LoggerFactory.getLogger("dev.liftgate.http")

fun Application.liftgate(app: App) {
    install(ContentNegotiation) {
        json(json)
        accept { _, _ -> listOf(ContentTypeWithQuality(ContentType.Application.Json)) }
    }
    install(CallId) { generate { UUID.randomUUID().toString() } }
    install(CallLogging) {
        callIdMdc("callId")
        disableDefaultColors()
    }
    install(MicrometerMetrics) { registry = app.metrics }
    install(WebSockets) { maxFrameSize = WEBSOCKET_FRAME_LIMIT }
    install(DefaultHeaders) {
        header(HttpHeaders.Server, "Liftgate")
        header(HttpHeaders.StrictTransportSecurity, "max-age=63072000")
        header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
    }
    val dashboard = Url(app.config.dashboardUrl)
    if (dashboard.protocolWithAuthority != Url(app.config.publicUrl).protocolWithAuthority) install(CORS) {
        allowHost(dashboard.hostWithPortIfSpecified, listOf(dashboard.protocol.name))
        allowCredentials = true
        allowHeader(HttpHeaders.ContentType)
        listOf(HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete).forEach(::allowMethod)
    }
    install(StatusPages) {
        exception<LiftgateException> { call, e -> call.respond(e.status, ErrorBody(e.code, e.message, e.field)) }
        exception<PayloadTooLargeException> { call, _ -> call.respond(HttpStatusCode.PayloadTooLarge, tooLarge) }
        exception<BadRequestException> { call, e ->
            if (generateSequence<Throwable>(e) { it.cause }.any { it is PayloadTooLargeException }) call.respond(HttpStatusCode.PayloadTooLarge, tooLarge)
            else call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", e.cause?.message ?: e.message ?: "bad request"))
        }
        exception<SerializationException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", e.message ?: "malformed body")) }
        exception<ExposedSQLException> { call, e ->
            if (e.sqlState == UNIQUE_VIOLATION) call.respond(HttpStatusCode.Conflict, ErrorBody("conflict", "already exists")) else internalError(call, e)
        }
        exception<Throwable> { call, e -> internalError(call, e) }
    }
    install(authPlugin(app))
    routing {
        install(RequestBodyLimit) { bodyLimit { BODY_LIMIT } }
        apiRoutes(app)
    }
}

fun Application.health(app: App) {
    install(MicrometerMetrics) { registry = app.metrics }
    routing { healthRoutes(app) }
}

fun httpServer(app: App) = embeddedServer(Netty, port = app.config.httpPort, host = "0.0.0.0") { if (app.runs(Role.API)) liftgate(app) else health(app) }

private suspend fun internalError(call: ApplicationCall, e: Throwable) {
    log.error("unhandled error on {} {}", call.request.httpMethod.value, call.request.path(), e)
    call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal_error", "internal error"))
}
