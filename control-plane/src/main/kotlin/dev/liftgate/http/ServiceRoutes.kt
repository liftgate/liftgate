package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.OrgRole
import dev.liftgate.service.EnvVar
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceScope
import dev.liftgate.service.ServiceSpec
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID

private const val MAX_REPLICAS = 10
private const val MAX_CPU_MILLIS = 4000
private const val MAX_MEMORY_MB = 8192
private val envVarName = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val repoPath = Regex("[A-Za-z0-9._/-]*")

fun Route.serviceRoutes(app: App) {
    route("/environments/{id}/services") {
        get { call.respond(app.services.forEnvironment(call.environment(app).id)) }
        post {
            val environment = call.environment(app, OrgRole.ADMIN)
            val service = app.services.create(environment.id, call.receive<ServiceSpec>().validated())
            try {
                app.claimPlatformDomain(app.services.scope(service.id) ?: notFound("service"))
            } catch (e: LiftgateException) {
                app.services.delete(service.id)
                throw e
            }
            call.respond(HttpStatusCode.Created, service)
        }
    }
    route("/services/{id}") {
        get { call.respond(call.service(app).service) }
        patch {
            val scope = call.service(app, OrgRole.ADMIN)
            val merged = JsonObject(json.encodeToJsonElement(scope.service.spec()).jsonObject + call.receive<JsonObject>())
            val spec = json.decodeFromJsonElement<ServiceSpec>(merged).validated()
            if (spec.slug != scope.service.slug) invalid("slug cannot be changed")
            app.claimPlatformDomain(scope.copy(service = scope.service.copy(slug = spec.slug, kind = spec.kind)))
            call.respond(app.services.update(scope.service.id, spec))
        }
        delete {
            app.services.delete(call.service(app, OrgRole.ADMIN).service.id)
            call.respond(HttpStatusCode.NoContent)
        }
        route("/env") {
            get { call.respond(app.envVars.list(call.service(app).service.id, reveal = false)) }
            put {
                val service = call.service(app, OrgRole.ADMIN).service
                val vars = call.receive<List<EnvVar>>()
                if (vars.any { !envVarName.matches(it.name) }) invalid("env var names must match [A-Za-z_][A-Za-z0-9_]*")
                if (vars.distinctBy { it.name }.size != vars.size) invalid("env var names must be unique")
                app.envVars.replace(service.id, vars)
                call.respond(app.envVars.list(service.id, reveal = false))
            }
        }
    }
}

suspend fun ApplicationCall.service(app: App, min: OrgRole = OrgRole.MEMBER, id: UUID = uuid("id")): ServiceScope {
    val scope = app.services.scope(id) ?: notFound("service")
    app.access.require(scope.org.id, principal, min)
    return scope
}

private fun ServiceSpec.validated(): ServiceSpec {
    requireSlug(slug)
    if (name.isBlank()) invalid("name is required")
    if (port != null && port !in 1..65535) invalid("port must be between 1 and 65535")
    if (replicas !in 0..MAX_REPLICAS) invalid("replicas must be between 0 and $MAX_REPLICAS")
    if (cpuMillis !in 1..MAX_CPU_MILLIS) invalid("cpuMillis must be between 1 and $MAX_CPU_MILLIS")
    if (memoryMb !in 1..MAX_MEMORY_MB) invalid("memoryMb must be between 1 and $MAX_MEMORY_MB")
    if (!rootDir.isRepoPath()) invalid("rootDir must be a path inside the repository")
    if (dockerfilePath.isBlank() || dockerfilePath.startsWith('/') || !dockerfilePath.isRepoPath()) invalid("dockerfilePath must be a relative path inside rootDir")
    if (kind == ServiceKind.CRON && cronSchedule.isNullOrBlank()) invalid("cron services need a cronSchedule")
    return this
}

private fun String.isRepoPath() = repoPath.matches(this) && ".." !in split('/')

private suspend fun App.claimPlatformDomain(scope: ServiceScope) {
    if (scope.service.kind == ServiceKind.WEB) domains.ensurePlatform(scope.service, scope.project, scope.org)
}
