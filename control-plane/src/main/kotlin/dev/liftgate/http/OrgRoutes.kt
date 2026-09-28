package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.OrgRole
import dev.liftgate.org.Organization
import dev.liftgate.org.User
import dev.liftgate.org.UserStatus
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

private val slugPattern = Regex("[a-z0-9][a-z0-9-]{0,38}[a-z0-9]")
private val reservedOrgSlugs = setOf(
    "account", "api", "login", "docs", "new", "settings", "admin", "status", "pricing", "blog", "changelog", "help", "support",
    "legal", "terms", "privacy", "security", "sso", "signup", "logout", "www", "app", "dashboard", "liftgate",
)
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
private data class CreateToken(val name: String, val expiresInDays: Int? = 90)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
private data class CreatedToken(val token: String)

fun Route.orgRoutes(app: App) {
    get("/me") { call.respond(call.principal.user) }
    delete("/me") {
        app.orgs.deleteUser(call.sessionUser.id)
        call.endSession(app)
        call.respond(HttpStatusCode.NoContent)
    }
    route("/orgs") {
        get {
            val principal = call.principal
            call.respond(app.orgs.forUser(principal.user.id).filter { principal.orgId == null || it.id == principal.orgId })
        }
        post {
            if (call.principal.token) forbidden()
            if (call.principal.user.status == UserStatus.PENDING) accountPending()
            val body = call.receive<CreateOrg>()
            requireSlug(body.slug, reservedOrgSlugs)
            if (body.name.isBlank()) invalid("name is required")
            call.respond(HttpStatusCode.Created, app.orgs.create(body.slug, body.name.trim(), call.principal.user.id))
        }
        route("/{slug}") {
            get { call.respond(call.org(app)) }
            delete {
                app.orgs.delete(call.sessionOrg(app, OrgRole.OWNER).id)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/members") { call.respond(app.orgs.members(call.org(app).id).map { (user, role) -> Member(user, role) }) }
            get("/usage") { call.respond(app.orgs.usage(call.org(app).id)) }
            route("/tokens") {
                get { call.respond(app.apiTokens.list(call.sessionOrg(app, OrgRole.ADMIN).id)) }
                post {
                    val org = call.sessionOrg(app, OrgRole.ADMIN)
                    val body = call.receive<CreateToken>()
                    val name = requireName(body.name)
                    if (body.expiresInDays != null && body.expiresInDays !in 1..365) invalid("expiresInDays must be 1 to 365, or null for a token that never expires")
                    call.respond(HttpStatusCode.Created, CreatedToken(app.apiTokens.create(org.id, name, call.principal.user.id, body.expiresInDays)))
                }
                delete("/{id}") {
                    app.apiTokens.delete(call.sessionOrg(app, OrgRole.ADMIN).id, call.uuid("id"), call.principal.user.id)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

fun requireSlug(slug: String, reserved: Set<String> = emptySet()) {
    if (!slugPattern.matches(slug)) invalid("slug must be 2 to 40 lowercase letters, digits or hyphens")
    if (slug in reserved) invalid("$slug is reserved, choose another slug")
}

fun requireName(name: String) = name.trim().takeIf { it.length in 1..100 } ?: invalid("name must be 1 to 100 characters")

suspend fun ApplicationCall.org(app: App, min: OrgRole = OrgRole.MEMBER): Organization {
    val org = app.orgs.bySlug(parameters["slug"]!!) ?: notFound("organization")
    app.access.require(org.id, principal, min)
    return org
}

suspend fun ApplicationCall.sessionOrg(app: App, min: OrgRole): Organization = if (principal.token) forbidden() else org(app, min)
