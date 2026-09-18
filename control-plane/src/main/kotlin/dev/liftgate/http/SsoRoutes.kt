package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.OrgRole
import dev.liftgate.auth.SsoSettings
import dev.liftgate.auth.randomToken
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

private const val BROWSER_COOKIE = "liftgate_saml"
private const val BROWSER_SECONDS = 600
private val samlMetadata = ContentType("application", "samlmetadata+xml")

/**
 * @author Dean
 * @date 9/18/2026
 */
@Serializable
data class SsoLookup(val org: String)

/**
 * @author Dean
 * @date 9/18/2026
 */
@Serializable
data class SsoServiceProvider(val entityId: String, val acsUrl: String)

fun Route.ssoRoutes(app: App) {
    route("/auth/sso") {
        get("/lookup") {
            val email = call.request.queryParameters["email"] ?: invalid("email is required")
            call.respond(SsoLookup(app.sso.lookup(email) ?: notFound("SSO connection")))
        }
        route("/{org}") {
            get("/login") {
                val browser = randomToken()
                call.response.cookies.append(app.cookie(BROWSER_COOKIE, browser, BROWSER_SECONDS).copy(secure = true, extensions = mapOf("SameSite" to "None")))
                call.respondRedirect(app.sso.loginUrl(call.parameters["org"]!!, safeNext(call.request.queryParameters["next"]).orEmpty(), browser))
            }
            post("/acs") {
                call.response.cookies.append(expired(BROWSER_COOKIE))
                call.redirectOnError(app, "/login") {
                    val response = call.receiveParameters()["SAMLResponse"] ?: invalid("SAMLResponse is required")
                    val (signedIn, next) = app.sso.acs(call.parameters["org"]!!, response, call.request.cookies[BROWSER_COOKIE].orEmpty())
                    call.startSession(app, signedIn.sessionId)
                    call.respondRedirect(app.config.dashboardUrl + next)
                }
            }
            get("/metadata") {
                val org = app.orgs.bySlug(call.parameters["org"]!!) ?: notFound("organization")
                call.respondText(app.sso.metadata(org.slug), samlMetadata)
            }
        }
    }
    route("/orgs/{slug}/sso") {
        get { call.respond(app.sso.settings(call.org(app, OrgRole.OWNER).id) ?: notFound("SSO connection")) }
        put { call.respond(app.sso.save(call.org(app, OrgRole.OWNER).id, call.receive<SsoSettings>())) }
        delete {
            app.sso.delete(call.org(app, OrgRole.OWNER).id)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/sp") {
            val org = call.org(app, OrgRole.OWNER).slug
            call.respond(SsoServiceProvider(app.sso.entityId(org), app.sso.acsUrl(org)))
        }
        post("/verify") { call.respond(app.sso.verifyDomains(call.org(app, OrgRole.OWNER).id)) }
    }
}
