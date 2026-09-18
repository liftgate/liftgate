package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.OrgRole
import dev.liftgate.org.Organization
import dev.liftgate.org.User
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

private val slugPattern = Regex("[a-z0-9][a-z0-9-]{0,38}[a-z0-9]")
private val reservedOrgSlugs = setOf("account", "api", "login")
val reservedProjectSlugs = setOf("settings")

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
private data class CreateOrg(val slug: String, val name: String)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
private data class Member(val user: User, val role: OrgRole)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
private data class CreateToken(val name: String)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
private data class CreatedToken(val token: String)

fun Route.orgRoutes(app: App) {
    get("/me") { call.respond(call.principal.user) }
    route("/orgs") {
        get {
            val principal = call.principal
            call.respond(app.orgs.forUser(principal.user.id).filter { principal.orgId == null || it.id == principal.orgId })
        }
        post {
            if (call.principal.token) forbidden()
            val body = call.receive<CreateOrg>()
            requireSlug(body.slug, reservedOrgSlugs)
            if (body.name.isBlank()) invalid("name is required")
            call.respond(HttpStatusCode.Created, app.orgs.create(body.slug, body.name.trim(), call.principal.user.id))
        }
        route("/{slug}") {
            get { call.respond(call.org(app)) }
            get("/members") { call.respond(app.orgs.members(call.org(app).id).map { (user, role) -> Member(user, role) }) }
            post("/tokens") {
                val principal = call.principal
                if (principal.token) forbidden()
                val org = call.org(app, OrgRole.ADMIN)
                val name = call.receive<CreateToken>().name.trim().ifEmpty { invalid("name is required") }
                call.respond(HttpStatusCode.Created, CreatedToken(app.apiTokens.create(org.id, name, principal.user.id)))
            }
        }
    }
}

fun requireSlug(slug: String, reserved: Set<String> = emptySet()) {
    if (!slugPattern.matches(slug)) invalid("slug must be 2 to 40 lowercase letters, digits or hyphens")
    if (slug in reserved) invalid("$slug is reserved, choose another slug")
}

suspend fun ApplicationCall.org(app: App, min: OrgRole = OrgRole.MEMBER): Organization {
    val org = app.orgs.bySlug(parameters["slug"]!!) ?: notFound("organization")
    app.access.require(org.id, principal, min)
    return org
}
