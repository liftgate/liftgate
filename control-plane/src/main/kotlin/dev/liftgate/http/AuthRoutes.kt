package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.GITHUB
import dev.liftgate.auth.randomToken
import dev.liftgate.auth.sessionLifetime
import dev.liftgate.db.sql
import dev.liftgate.org.User
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.date.GMTDate
import kotlinx.serialization.Serializable

private const val STATE_COOKIE = "liftgate_oauth_state"
private const val STATE_SECONDS = 600

/**
 * @author Dean
 * @date 9/18/2026
 */
private enum class Intent { SIGNIN, LINK, CONNECT }

/**
 * @author Dean
 * @date 9/18/2026
 */
@Serializable
data class AuthProviders(val oauth: List<String>, val passkey: Boolean, val email: Boolean, val sso: Boolean)

fun App.authProviders() = AuthProviders(oauth.providers.keys.toList(), passkey = true, email = emailCodes != null, sso = true)

fun safeNext(next: String?): String? = next?.takeIf { it.startsWith("/") && !it.startsWith("//") && '\\' !in it }

val ApplicationCall.sessionUser: User
    get() = principal.takeUnless { it.token }?.user ?: forbidden()

fun ApplicationCall.startSession(app: App, sessionId: String) =
    response.cookies.append(app.cookie(SESSION_COOKIE, sessionId, sessionLifetime.inWholeSeconds.toInt()))

suspend fun ApplicationCall.redirectOnError(app: App, back: String, block: suspend () -> Unit) = try {
    block()
} catch (e: LiftgateException) {
    respondRedirect(URLBuilder(app.config.dashboardUrl + back).apply { parameters.append("error", e.code) }.buildString())
}

fun Route.authRoutes(app: App) {
    route("/auth") {
        get("/providers") { call.respond(app.authProviders()) }
        get("/{provider}/login") {
            val provider = app.oauth.provider(call.parameters["provider"]!!)
            val intent = parseIntent(call.request.queryParameters["intent"] ?: Intent.SIGNIN.sql)
            if (intent != Intent.SIGNIN && call.principal.token) forbidden()
            if (intent == Intent.CONNECT && provider.id != GITHUB) invalid("only github can be connected")
            val state = randomToken()
            val verifier = randomToken()
            val flow = listOf(state, verifier, intent.sql, safeNext(call.request.queryParameters["next"]).orEmpty())
            call.response.cookies.append(app.cookie(STATE_COOKIE, flow.joinToString("|"), STATE_SECONDS))
            call.respondRedirect(app.oauth.loginUrl(provider, state, verifier))
        }
        get("/{provider}/callback") {
            val flow = call.request.cookies[STATE_COOKIE]?.split('|', limit = 4)?.takeIf { it.size == 4 && it[0] == call.request.queryParameters["state"] }
            call.response.cookies.append(expired(STATE_COOKIE))
            call.redirectOnError(app, if (flow == null || flow[2] == Intent.SIGNIN.sql) "/login" else flow[3].ifEmpty { "/account" }) {
                val (_, verifier, intent, next) = flow ?: throw LiftgateException(HttpStatusCode.BadRequest, "invalid_state", "OAuth state mismatch")
                call.request.queryParameters["error"]?.let {
                    throw LiftgateException(HttpStatusCode.BadRequest, if (it == "access_denied") it else "oauth_failed", "the provider did not sign you in")
                }
                val provider = app.oauth.provider(call.parameters["provider"]!!)
                val tokens = app.oauth.exchange(provider, call.request.queryParameters["code"] ?: invalid("code is required"), verifier)
                val identity = app.oauth.identity(provider, tokens)
                val userId = when (parseIntent(intent)) {
                    Intent.SIGNIN -> app.signIn.complete(identity).also { call.startSession(app, it.sessionId) }.userId
                    Intent.LINK -> call.sessionUser.id.also { app.signIn.link(it, identity) }
                    Intent.CONNECT -> call.sessionUser.id
                }
                if (provider.id == GITHUB) app.gitConnections.store(userId, identity.login ?: identity.subject, tokens)
                call.respondRedirect(app.config.dashboardUrl + next)
            }
        }
        post("/logout") {
            call.request.cookies[SESSION_COOKIE]?.let { app.sessions.delete(it) }
            call.response.cookies.append(expired(SESSION_COOKIE))
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/me") {
        get("/identities") { call.respond(app.signIn.identities(call.sessionUser.id)) }
        delete("/identities/{id}") {
            app.signIn.unlink(call.sessionUser.id, call.uuid("id"))
            call.respond(HttpStatusCode.NoContent)
        }
        get("/connections") { call.respond(app.gitConnections.list(call.sessionUser.id)) }
        delete("/connections/{provider}") {
            app.gitConnections.delete(call.sessionUser.id, call.parameters["provider"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun parseIntent(name: String) = Intent.entries.firstOrNull { it.sql == name } ?: invalid("intent must be signin, link or connect")

fun expired(name: String) = Cookie(name = name, value = "", expires = GMTDate.START, path = "/")

fun App.cookie(name: String, value: String, maxAge: Int) = Cookie(
    name = name,
    value = value,
    maxAge = maxAge,
    path = "/",
    secure = config.publicUrl.startsWith("https"),
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)
