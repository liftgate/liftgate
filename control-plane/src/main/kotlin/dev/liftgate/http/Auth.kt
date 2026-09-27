package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.Principal
import dev.liftgate.org.UserStatus
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.protocolWithAuthority
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.authorization
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.util.AttributeKey

const val SESSION_COOKIE = "__Host-liftgate_session"
val safeMethods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)
private val principalKey = AttributeKey<Principal>("liftgate.principal")

val ApplicationCall.principal: Principal
    get() = principalOrNull ?: unauthorized()

val ApplicationCall.principalOrNull: Principal?
    get() = attributes.getOrNull(principalKey)

fun authPlugin(app: App) = createApplicationPlugin("LiftgateAuth") {
    val origins = setOf(app.config.dashboardUrl, app.config.publicUrl).map { Url(it).protocolWithAuthority }
    onCall { call ->
        val principal = app.resolve(call)?.takeIf { it.user.status != UserStatus.SUSPENDED } ?: return@onCall
        val guarded = call.request.httpMethod !in safeMethods || call.request.headers.contains(HttpHeaders.Upgrade)
        if (!principal.token && guarded && call.request.header(HttpHeaders.Origin) !in origins) {
            throw LiftgateException(HttpStatusCode.Forbidden, "bad_origin", "requests signed in with a session cookie must come from the dashboard")
        }
        call.attributes.put(principalKey, principal)
    }
}

private suspend fun App.resolve(call: ApplicationCall): Principal? {
    val bearer = call.request.authorization()?.removePrefix("Bearer ")?.takeIf { it.startsWith("lg_") }
    if (bearer != null) return apiTokens.resolve(bearer)?.let { (orgId, userId) -> orgs.user(userId)?.let { Principal(it, token = true, orgId = orgId) } }
    return call.request.cookies[SESSION_COOKIE]?.let { sessions.resolve(it) }?.let { Principal(it, token = false) }
}
