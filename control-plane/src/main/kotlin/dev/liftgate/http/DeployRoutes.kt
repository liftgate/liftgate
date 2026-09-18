package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.OrgRole
import dev.liftgate.deploy.Build
import dev.liftgate.service.ServiceScope
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

private val refPattern = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
private val shaPattern = Regex("[0-9a-f]{40}")

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
private data class DeployRequest(val ref: String? = null)

fun Route.deployRoutes(app: App) {
    route("/services/{id}") {
        post("/deploy") {
            val scope = call.service(app, OrgRole.ADMIN)
            val ref = call.receive<DeployRequest>().ref ?: scope.environment.branch
            if (!refPattern.matches(ref)) invalid("ref must be a commit sha or branch name")
            val (sha, message) = if (shaPattern.matches(ref)) ref to null else app.branchHead(scope, ref)
            call.respond(HttpStatusCode.Created, app.builds.request(scope.service.id, sha, message, scope.environment.branch))
        }
        get("/builds") { call.respond(app.builds.forService(call.service(app).service.id)) }
        get("/deployments") { call.respond(app.deployments.forService(call.service(app).service.id)) }
    }
    get("/builds/{id}") { call.respond(call.build(app)) }
    post("/deployments/{id}/rollback") {
        val deployment = app.deployments.byId(call.uuid("id")) ?: notFound("deployment")
        call.service(app, OrgRole.ADMIN, deployment.serviceId)
        call.respond(HttpStatusCode.Created, app.deployments.rollback(deployment.id))
    }
}

private suspend fun App.branchHead(scope: ServiceScope, ref: String): Pair<String, String?> {
    val github = github ?: throw LiftgateException(HttpStatusCode.UnprocessableEntity, "ref_required", "ref must be a full commit sha")
    return try {
        github.branchHead(scope.project.installationId, scope.project.repoFullName, ref)
    } catch (e: ResponseException) {
        invalid("GitHub could not resolve $ref of ${scope.project.repoFullName}")
    }
}

suspend fun ApplicationCall.build(app: App): Build {
    val build = app.builds.byId(uuid("id")) ?: notFound("build")
    service(app, id = build.serviceId)
    return build
}
