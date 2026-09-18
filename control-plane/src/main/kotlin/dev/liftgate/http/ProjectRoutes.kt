package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.OrgRole
import dev.liftgate.project.Environment
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.Project
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

private val repoPattern = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
private data class CreateProject(val slug: String, val name: String, val repoFullName: String)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
private data class CreateEnvironment(val slug: String, val name: String, val kind: EnvironmentKind = EnvironmentKind.PRODUCTION, val branch: String)

fun Route.projectRoutes(app: App) {
    route("/orgs/{slug}/projects") {
        get { call.respond(app.projects.forOrg(call.org(app).id)) }
        post {
            val org = call.org(app, OrgRole.ADMIN)
            val body = call.receive<CreateProject>()
            requireSlug(body.slug, reservedProjectSlugs)
            if (body.name.isBlank()) invalid("name is required")
            if (!repoPattern.matches(body.repoFullName)) invalid("repoFullName must be owner/repo")
            val token = app.gitConnections.githubToken(call.principal.user.id)
                ?: throw LiftgateException(HttpStatusCode.Conflict, "github_not_connected", "connect GitHub to import a repository")
            val installationId = app.github?.installation(token, body.repoFullName)
                ?: invalid("install the GitHub App on ${body.repoFullName} from an account that can push to it")
            call.respond(HttpStatusCode.Created, app.projects.create(org.id, body.slug, body.name.trim(), body.repoFullName, installationId))
        }
    }
    route("/projects/{id}") {
        get { call.respond(call.project(app)) }
        delete {
            app.projects.delete(call.project(app, OrgRole.ADMIN).id)
            call.respond(HttpStatusCode.NoContent)
        }
        route("/environments") {
            get { call.respond(app.projects.environments(call.project(app).id)) }
            post {
                val project = call.project(app, OrgRole.ADMIN)
                val body = call.receive<CreateEnvironment>()
                requireSlug(body.slug, reservedProjectSlugs)
                if (body.name.isBlank() || body.branch.isBlank()) invalid("name and branch are required")
                call.respond(HttpStatusCode.Created, app.projects.createEnvironment(project.id, body.slug, body.name.trim(), body.kind, body.branch.trim()))
            }
        }
    }
}

suspend fun ApplicationCall.project(app: App, min: OrgRole = OrgRole.MEMBER): Project {
    val project = app.projects.byId(uuid("id")) ?: notFound("project")
    app.access.require(project.orgId, principal, min)
    return project
}

suspend fun ApplicationCall.environment(app: App, min: OrgRole = OrgRole.MEMBER): Environment {
    val environment = app.projects.environment(uuid("id")) ?: notFound("environment")
    val project = app.projects.byId(environment.projectId) ?: notFound("project")
    app.access.require(project.orgId, principal, min)
    return environment
}
