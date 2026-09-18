package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.randomToken
import dev.liftgate.auth.sessionLifetime
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.date.GMTDate

private const val STATE_COOKIE = "liftgate_oauth_state"
private const val STATE_SECONDS = 600

fun Route.authRoutes(app: App) {
    val oauth = app.oauth ?: return
    route("/auth") {
        get("/github/login") {
            val state = randomToken()
            call.response.cookies.append(app.cookie(STATE_COOKIE, state, STATE_SECONDS))
            call.respondRedirect(oauth.loginUrl(state))
        }
        get("/github/callback") {
            val state = call.request.cookies[STATE_COOKIE]
            if (state == null || state != call.request.queryParameters["state"]) throw LiftgateException(HttpStatusCode.BadRequest, "invalid_state", "OAuth state mismatch")
            val code = call.request.queryParameters["code"] ?: invalid("code is required")
            val (github, token) = oauth.exchange(code)
            val user = app.orgs.upsertUser(github.id, github.login, github.name, github.email, github.avatarUrl)
            call.response.cookies.append(app.cookie(SESSION_COOKIE, app.sessions.create(user.id, token), sessionLifetime.inWholeSeconds.toInt()))
            call.response.cookies.append(expired(STATE_COOKIE))
            call.respondRedirect(app.config.dashboardUrl)
        }
        post("/logout") {
            call.request.cookies[SESSION_COOKIE]?.let { app.sessions.delete(it) }
            call.response.cookies.append(expired(SESSION_COOKIE))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun expired(name: String) = Cookie(name = name, value = "", expires = GMTDate.START, path = "/")

private fun App.cookie(name: String, value: String, maxAge: Int) = Cookie(
    name = name,
    value = value,
    maxAge = maxAge,
    path = "/",
    secure = config.publicUrl.startsWith("https"),
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)
