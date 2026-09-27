package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.build.REGISTRY_TOKEN_SECONDS
import io.ktor.server.auth.basicAuthenticationCredentials
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Route.registryRoutes(app: App) {
    get("/registry/token") {
        if (app.config.registryTokens == null) notFound("registry token service")
        val credentials = call.request.basicAuthenticationCredentials() ?: unauthorized()
        val scopes = call.request.queryParameters.getAll("scope").orEmpty().flatMap { it.split(' ') }
        val token = app.registryTokens.token(credentials.name, credentials.password, scopes) ?: unauthorized()
        call.respond(buildJsonObject {
            put("token", token)
            put("expires_in", REGISTRY_TOKEN_SECONDS)
        })
    }
}
