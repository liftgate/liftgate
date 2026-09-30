package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.OrgRole
import dev.liftgate.auth.audit
import dev.liftgate.org.Organization
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingPipelineCall
import io.ktor.server.routing.path
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import java.util.UUID

private const val API_PREFIX = "/api/v1"
private val auditOrgKey = AttributeKey<UUID>("liftgate.audit.org")
private val auditDetailsKey = AttributeKey<Map<String, String>>("liftgate.audit.details")

fun ApplicationCall.auditOrg(orgId: UUID?) = if (orgId == null) attributes.remove(auditOrgKey) else attributes.put(auditOrgKey, orgId)

fun ApplicationCall.auditDetails(vararg names: Pair<String, String>) = attributes.put(auditDetailsKey, attributes.getOrNull(auditDetailsKey).orEmpty() + names)

suspend fun ApplicationCall.authorize(app: App, orgId: UUID, min: OrgRole = OrgRole.MEMBER) {
    app.access.require(orgId, principal, min)
    auditOrg(orgId)
}

fun ApplicationCall.authorize(app: App, org: Organization, min: OrgRole = OrgRole.MEMBER) {
    app.access.require(org, principal, min)
    auditOrg(org.id)
}

fun Route.audit(app: App) {
    val plugin = createRouteScopedPlugin("Audit") {
        onCallRespond { call, body ->
            val routing = call as? RoutingPipelineCall ?: return@onCallRespond
            val principal = call.principalOrNull ?: return@onCallRespond
            val status = call.response.status() ?: body as? HttpStatusCode ?: HttpStatusCode.OK
            if (call.request.httpMethod in safeMethods || !status.isSuccess()) return@onCallRespond
            val org = call.attributes.getOrNull(auditOrgKey)
            val (type, id) = generateSequence(routing.route) { it.parent }.firstNotNullOfOrNull { node ->
                (node.selector as? PathSegmentParameterRouteSelector)?.takeIf { it.name != "token" }?.let { node.parent?.selector.toString() to routing.pathParameters[it.name].orEmpty() }
            } ?: ("orgs" to org.toString())
            val action = "${call.request.httpMethod.value} ${routing.route.path.removePrefix(API_PREFIX)}"
            app.db.tx { audit(principal.user.id, action, type, id, call.attributes.getOrNull(auditDetailsKey).orEmpty(), org, principal.token) }
        }
    }
    orgScopedPaths.forEach { route(it) { install(plugin) } }
}
