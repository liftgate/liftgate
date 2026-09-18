package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.Principal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.authorization
import io.ktor.util.AttributeKey

const val SESSION_COOKIE = "liftgate_session"
private val principalKey = AttributeKey<Principal>("liftgate.principal")

val ApplicationCall.principal: Principal
    get() = attributes.getOrNull(principalKey) ?: unauthorized()

fun authPlugin(app: App) = createApplicationPlugin("LiftgateAuth") {
    onCall { call -> app.resolve(call)?.let { call.attributes.put(principalKey, it) } }
}

private suspend fun App.resolve(call: ApplicationCall): Principal? {
    val bearer = call.request.authorization()?.removePrefix("Bearer ")?.takeIf { it.startsWith("lg_") }
    if (bearer != null) return apiTokens.resolve(bearer)?.let { (orgId, userId) -> orgs.user(userId)?.let { Principal(it, token = true, orgId = orgId) } }
    return call.request.cookies[SESSION_COOKIE]?.let { sessions.resolve(it) }?.let { Principal(it, token = false) }
}
