package dev.liftgate.http

import dev.liftgate.App
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.githubRoutes(app: App) {
    get("/me/github/repositories") {
        val github = app.github ?: conflict("the GitHub App is not configured")
        val (token) = app.gitConnections.github(call.sessionUser.id) ?: githubNotConnected()
        call.respond(
            try {
                github.importable(token)
            } catch (e: ClientRequestException) {
                if (e.response.status == HttpStatusCode.Unauthorized) githubNotConnected() else throw e
            },
        )
    }
}
