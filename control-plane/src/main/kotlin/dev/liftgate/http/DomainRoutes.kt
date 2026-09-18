package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.OrgRole
import dev.liftgate.domain.Domain
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

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
private data class AddDomain(val hostname: String)

fun Route.domainRoutes(app: App) {
    route("/services/{id}/domains") {
        get { call.respond(app.domains.forService(call.service(app).service.id)) }
        post {
            val service = call.service(app, OrgRole.ADMIN).service
            call.respond(HttpStatusCode.Created, app.domains.addCustom(service.id, call.receive<AddDomain>().hostname))
        }
    }
    route("/domains/{id}") {
        post("/verify") { call.respond(app.domains.verify(call.domain(app).id)) }
        delete {
            app.domains.delete(call.domain(app).id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun ApplicationCall.domain(app: App): Domain {
    val domain = app.domains.byId(uuid("id")) ?: notFound("domain")
    service(app, OrgRole.ADMIN, domain.serviceId)
    return domain
}
