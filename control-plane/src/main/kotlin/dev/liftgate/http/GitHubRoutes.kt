package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.org.UserStatus
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
    get("/me/github/repositories/detect") {
        val user = call.sessionUser
        if (user.status == UserStatus.PENDING) accountPending()
        val github = app.github ?: conflict("the GitHub App is not configured")
        val repo = call.request.queryParameters["repo"]?.takeIf(repoPattern::matches) ?: invalid("the repository must be owner/name", "repo")
        val ref = call.ref()
        call.limit(app, "detect", DETECTS_PER_MINUTE, user.id.toString())
        val (token) = app.gitConnections.github(user.id) ?: githubNotConnected()
        val (installationId, defaultBranch) = github.installation(token, repo) ?: invalid("install the GitHub App on $repo from an account that can push to it", "repo")
        call.respond(app.detect(installationId, repo, ref ?: defaultBranch))
    }
}
