package dev.liftgate.http

import dev.liftgate.App
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

private const val CHALLENGE_COOKIE = "liftgate_passkey"
private const val CHALLENGE_SECONDS = 300

/**
 * @author Dean
 * @date 9/18/2026
 */
@Serializable
data class PasskeyAssertion(val credential: JsonObject)

/**
 * @author Dean
 * @date 9/18/2026
 */
@Serializable
data class PasskeyRegistration(val credential: JsonObject, val name: String)

fun Route.passkeyRoutes(app: App) {
    post("/auth/passkey/options") { call.respondChallenge(app, app.passkeys.assertionOptions()) }
    post("/auth/passkey/verify") {
        val signedIn = app.passkeys.verify(call.takeChallenge(), call.receive<PasskeyAssertion>().credential.toString())
        call.startSession(app, signedIn.sessionId)
        call.respond(HttpStatusCode.NoContent)
    }
    route("/me/passkeys") {
        get { call.respond(app.passkeys.list(call.sessionUser.id)) }
        post("/options") { call.respondChallenge(app, app.passkeys.registrationOptions(call.sessionUser)) }
        post {
            val body = call.receive<PasskeyRegistration>()
            val name = body.name.trim().ifEmpty { invalid("name is required") }
            call.respond(HttpStatusCode.Created, app.passkeys.register(call.sessionUser, call.takeChallenge(), body.credential.toString(), name))
        }
        delete("/{id}") {
            app.passkeys.delete(call.sessionUser.id, call.uuid("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun ApplicationCall.respondChallenge(app: App, challenge: Pair<String, String>) {
    response.cookies.append(app.cookie(CHALLENGE_COOKIE, challenge.first, CHALLENGE_SECONDS))
    respondText(challenge.second, ContentType.Application.Json)
}

private fun ApplicationCall.takeChallenge() = request.cookies[CHALLENGE_COOKIE].also { response.cookies.append(expired(CHALLENGE_COOKIE)) }
