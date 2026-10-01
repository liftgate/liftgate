package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.OrgRole
import dev.liftgate.build.Previews
import dev.liftgate.project.Environment
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.PreviewSettings
import dev.liftgate.project.Project
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

val repoPattern = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
private val previewSlug = Regex("pr-[0-9]+")

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
            val github = app.github ?: conflict("the GitHub App is not configured")
            val body = call.receive<CreateProject>()
            requireSlug(body.slug, reservedProjectSlugs)
            if (body.name.isBlank()) invalid("name is required", "name")
            if (!repoPattern.matches(body.repoFullName)) invalid("the repository must be owner/name", "repoFullName")
            val (token, login) = app.gitConnections.github(call.principal.user.id) ?: githubNotConnected()
            val (installationId, defaultBranch) = github.installation(token, body.repoFullName)
                ?: invalid("install the GitHub App on ${body.repoFullName} from an account that can push to it", "repoFullName")
            call.respond(HttpStatusCode.Created, app.projects.create(org.id, body.slug, body.name.trim(), body.repoFullName, installationId, login, defaultBranch))
        }
        get("/{project}/tree") {
            val tree = app.services.tree(call.parameters["slug"]!!, call.parameters["project"]!!, call.principal.user.id) ?: notFound("project")
            call.authorize(app, tree.project.orgId)
            call.respond(tree)
        }
    }
    route("/projects/{id}") {
        get { call.respond(call.project(app)) }
        patch {
            val project = call.project(app, OrgRole.ADMIN)
            val current = json.encodeToJsonElement(PreviewSettings(project.previewsEnabled, project.previewBaseEnvironmentId)).jsonObject
            val settings = json.decodeFromJsonElement<PreviewSettings>(JsonObject(current + call.receive<JsonObject>()))
            settings.previewBaseEnvironmentId?.let { id ->
                app.projects.environment(id)?.takeIf { it.projectId == project.id && it.pullRequest == null }
                    ?: invalid("the base environment must be one of the project's environments", "previewBaseEnvironmentId")
            }
            call.respond(app.projects.update(project.id, settings))
        }
        delete {
            app.projects.delete(call.project(app, OrgRole.ADMIN).id)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/previews") { call.respond(app.previews.status(call.project(app))) }
        get("/detect") {
            val project = call.project(app, OrgRole.ADMIN)
            call.limit(app, "detect", DETECTS_PER_MINUTE, call.principal.user.id.toString())
            call.respond(app.detect(project.installationId, project.repoFullName, call.ref() ?: project.repoDefaultBranch))
        }
        post("/previews/approve") {
            val project = call.project(app, OrgRole.ADMIN)
            val approval = call.receive<Previews.Approval>()
            call.auditDetails("pullRequest" to approval.number.toString(), "sha" to approval.sha)
            app.previews.approve(project, approval.number, approval.sha)
            call.respond(HttpStatusCode.NoContent)
        }
        route("/environments") {
            get { call.respond(app.projects.environments(call.project(app).id)) }
            post {
                val project = call.project(app, OrgRole.ADMIN)
                val body = call.receive<CreateEnvironment>()
                requireSlug(body.slug, reservedProjectSlugs)
                if (previewSlug.matches(body.slug)) invalid("${body.slug} is kept for the preview of pull request #${body.slug.drop(3)}", "slug")
                if (body.name.isBlank() || body.branch.isBlank()) invalid("name and branch are required")
                call.respond(HttpStatusCode.Created, app.projects.createEnvironment(project.id, body.slug, body.name.trim(), body.kind, body.branch.trim()))
            }
        }
    }
    delete("/environments/{id}") {
        app.projects.deleteEnvironment(call.environment(app, OrgRole.ADMIN).id)
        call.respond(HttpStatusCode.NoContent)
    }
}

suspend fun ApplicationCall.project(app: App, min: OrgRole = OrgRole.MEMBER): Project {
    val project = app.projects.byId(uuid("id")) ?: notFound("project")
    authorize(app, project.orgId, min)
    return project
}

suspend fun ApplicationCall.environment(app: App, min: OrgRole = OrgRole.MEMBER): Environment {
    val environment = app.projects.environment(uuid("id")) ?: notFound("environment")
    val project = app.projects.byId(environment.projectId) ?: notFound("project")
    authorize(app, project.orgId, min)
    return environment
}
