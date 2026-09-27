package dev.liftgate.http

import dev.liftgate.App
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlin.time.Duration.Companion.minutes

const val AUTH_PER_MINUTE = 60
const val WRITES_PER_MINUTE = 120
const val DEPLOYS_PER_MINUTE = 10
const val WEBHOOKS_PER_MINUTE = 60

fun Route.rateLimits(app: App) {
    route("/auth") { rateLimit(app, "auth", AUTH_PER_MINUTE) }
    listOf("/orgs", "/projects", "/environments", "/services", "/deployments", "/domains").forEach {
        route(it) { rateLimit(app, "writes", WRITES_PER_MINUTE) { if (request.httpMethod in safeMethods) null else caller(app) } }
    }
}

fun Route.rateLimit(app: App, name: String, perMinute: Int, key: ApplicationCall.() -> String? = { caller(app) }) {
    install(createRouteScopedPlugin("RateLimit.$name") { onCall { call -> call.key()?.let { call.limit(app, name, perMinute, it) } } })
}

fun ApplicationCall.limit(app: App, name: String, perMinute: Int, key: String) {
    if (app.cache.allow("rate:$name:$key", perMinute, 1.minutes)) return
    response.header(HttpHeaders.RetryAfter, 60 - System.currentTimeMillis() / 1000 % 60)
    throw LiftgateException(HttpStatusCode.TooManyRequests, "rate_limited", "too many requests, try again later")
}

private fun ApplicationCall.caller(app: App) = principalOrNull?.user?.id?.toString() ?: clientIp(app.config)
