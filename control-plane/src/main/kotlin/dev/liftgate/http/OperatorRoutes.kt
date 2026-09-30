package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.db.sql
import dev.liftgate.org.User
import dev.liftgate.org.UserStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

suspend fun ApplicationCall.operator(app: App): User? =
    principalOrNull?.takeUnless { it.token }?.user?.takeIf { app.config.operators.isNotEmpty() && app.admin.isOperator(it.id) }

fun Route.operatorRoutes(app: App) {
    route("/operator") {
        install(
            createRouteScopedPlugin("Operator") {
                onCall { call ->
                    val operator = call.operator(app) ?: return@onCall call.respond(HttpStatusCode.NotFound)
                    if (call.request.httpMethod !in safeMethods) call.limit(app, "writes", WRITES_PER_MINUTE, operator.id.toString())
                }
            },
        )
        route("{...}") { handle { call.respond(HttpStatusCode.NotFound) } }
        get("/summary") { call.respond(app.admin.summary()) }
        route("/users") {
            get {
                val status = call.request.queryParameters["status"]?.let { name -> UserStatus.entries.firstOrNull { it.sql == name } ?: invalid("status must be pending, active or suspended", "status") }
                call.respond(app.admin.users(status, call.before(), call.pageSize()))
            }
            route("/{id}") {
                post("/approve") { call.done(app.admin.approve(call.uuid("id").toString(), call.principal.user.id)) }
                post("/suspend") { call.done(app.admin.suspendUser(call.uuid("id").toString(), call.field("reason"), call.principal.user.id)) }
                post("/unsuspend") { call.done(app.admin.unsuspendUser(call.uuid("id").toString(), call.principal.user.id)) }
            }
        }
        route("/orgs") {
            get { call.respond(app.admin.orgs(call.before(), call.pageSize())) }
            route("/{slug}") {
                put("/plan") { call.done(app.admin.setPlan(call.parameters["slug"]!!, call.field("plan"), call.principal.user.id)) }
                post("/suspend") { call.done(app.admin.suspendOrg(call.parameters["slug"]!!, call.field("reason"), call.principal.user.id)) }
                post("/unsuspend") { call.done(app.admin.unsuspendOrg(call.parameters["slug"]!!, call.principal.user.id)) }
            }
        }
    }
}

private fun ApplicationCall.before() = request.queryParameters["before"]?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: invalid("before must be an id from the previous page", "before") }

private suspend fun ApplicationCall.field(name: String) = receive<Map<String, String>>()[name]?.trim()?.takeIf(String::isNotEmpty) ?: invalid("$name is required", name)

private suspend fun ApplicationCall.done(message: String) = respond(mapOf("message" to message))
