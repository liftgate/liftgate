package dev.liftgate.http

import dev.liftgate.App
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/18/2026
 */
@Serializable
data class EmailStart(val email: String)

/**
 * @author Dean
 * @date 9/18/2026
 */
@Serializable
data class EmailVerify(val email: String, val code: String)

fun Route.emailRoutes(app: App) {
    route("/auth/email") {
        post("/start") {
            app.emailCodes().start(call.receive<EmailStart>().email, call.clientIp(app.config.trustedProxies))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/verify") {
            val body = call.receive<EmailVerify>()
            call.startSession(app, app.emailCodes().verify(body.email, body.code).sessionId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun App.emailCodes() = emailCodes ?: notFound("email sign-in")
