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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID

private const val MAX_REPLICAS = 10
const val MAX_CPU_MILLIS = 4000
const val MAX_MEMORY_MB = 8192
const val MAX_STORAGE_GB = 100
private const val MAX_PATH = 256
const val MAX_WATCH_PATHS = 20
private const val MAX_WATCH_PATH_LENGTH = 100
private const val MAX_BUILD_COMMAND = 1000
val envVarName = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val repoPath = Regex("[A-Za-z0-9._/-]*")
private val frameworkId = Regex("[a-z0-9-]{1,40}")

fun Route.serviceRoutes(app: App) {
    route("/environments/{id}/services") {
        get { call.respond(app.services.withStatus(app.services.forEnvironment(call.environment(app).id))) }
        post {
            val environment = call.environment(app, OrgRole.ADMIN)
            val payload = call.receive<JsonObject>()
            val env = payload["env"]?.let { call.validEnv(json.decodeFromJsonElement(it)) }
            val spec = json.decodeFromJsonElement<ServiceSpec>(payload).validated()
            if (spec.volume != null && app.config.storageClass == null) storageNotConfigured()
            val service = app.services.create(environment.id, spec)
            val build = try {
                env?.let { app.envVars.replace(service.id, it) }
                val scope = app.services.scope(service.id) ?: notFound("service")
                app.claimPlatformDomain(scope)
                if (call.request.queryParameters["deploy"] != "true") null
                else app.branchHead(scope.project, environment.branch).let { (sha, message) -> app.builds.request(service.id, sha, message, environment.branch) }
            } catch (e: Exception) {
                app.services.delete(service.id)
                throw e
            }
            val body = json.encodeToJsonElement(service).jsonObject
            call.respond(HttpStatusCode.Created, build?.let { JsonObject(body + ("buildId" to JsonPrimitive(it.id.toString()))) } ?: body)
        }
    }
    route("/services/{id}") {
        get { call.respond(app.services.withStatus(listOf(call.service(app).service)).single()) }
        patch {
            val scope = call.service(app, OrgRole.ADMIN)
            val merged = JsonObject(json.encodeToJsonElement(scope.service.spec()).jsonObject + call.receive<JsonObject>())
            val spec = json.decodeFromJsonElement<ServiceSpec>(merged).validated()
            if (spec.slug != scope.service.slug) invalid("slug cannot be changed")
            scope.service.volume?.let { if (spec.volume == null || spec.volume.sizeGb < it.sizeGb) invalid("a volume can grow but not shrink or go away; delete the service to remove it", "volume") }
            if (spec.volume != scope.service.volume && app.config.storageClass == null) storageNotConfigured()
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
                val vars = call.validEnv(call.receive())
                app.envVars.replace(service.id, vars)
                call.respond(app.envVars.list(service.id, reveal = false))
            }
        }
    }
}

suspend fun ApplicationCall.service(app: App, min: OrgRole = OrgRole.MEMBER, id: UUID = uuid("id")): ServiceScope {
    val scope = app.services.scope(id, principalOrNull?.user?.id) ?: notFound("service")
    authorize(app, scope.org, min)
    return scope
}

private fun ServiceSpec.validated(): ServiceSpec {
    requireSlug(slug)
    if (name.isBlank()) invalid("name is required", "name")
    if (port != null && port !in 1..65535) invalid("the port must be between 1 and 65535", "port")
    if (replicas !in 0..MAX_REPLICAS) invalid("replicas must be between 0 and $MAX_REPLICAS", "replicas")
    if (cpuMillis !in 1..MAX_CPU_MILLIS) invalid("CPU must be between 1 and $MAX_CPU_MILLIS millicores", "cpuMillis")
    if (memoryMb !in 1..MAX_MEMORY_MB) invalid("memory must be between 1 and $MAX_MEMORY_MB MB", "memoryMb")
    if (!rootDir.isRepoPath()) invalid("the root directory must be a path inside the repository", "rootDir")
    if (dockerfilePath.isBlank() || dockerfilePath.startsWith('/') || !dockerfilePath.isRepoPath()) invalid("the Dockerfile path must be relative to the root directory and stay inside it", "dockerfilePath")
    if (kind == ServiceKind.CRON && cronSchedule.isNullOrBlank()) invalid("cron services need a schedule", "cronSchedule")
    if (healthCheckPath != null && (!healthCheckPath.startsWith('/') || healthCheckPath.length > MAX_PATH)) invalid("the health check path must start with / and be at most $MAX_PATH characters", "healthCheckPath")
    if (healthCheckPath != null && port == null && !kind.servesHttp) invalid("a health check path needs a port to probe", "healthCheckPath")
    if (volume != null && (volume.mountPath == "/" || !volume.mountPath.startsWith('/') || volume.mountPath.length > MAX_PATH || !volume.mountPath.isRepoPath())) {
        invalid("the volume mount path must be an absolute path such as /data", "volume")
    }
    if (volume != null && volume.sizeGb !in 1..MAX_STORAGE_GB) invalid("a volume must be between 1 and $MAX_STORAGE_GB GB", "volume")
    if (volume != null && replicas > 1) invalid("a service with a volume runs at most 1 replica", "replicas")
    if (watchPaths.size > MAX_WATCH_PATHS || watchPaths.any { it.isBlank() || it.length > MAX_WATCH_PATH_LENGTH }) invalid("list at most $MAX_WATCH_PATHS watch paths of up to $MAX_WATCH_PATH_LENGTH characters each", "watchPaths")
    if (buildCommand != null && (buildCommand.length > MAX_BUILD_COMMAND || buildCommand.lines().size > 1 || '\u0000' in buildCommand)) invalid("the build command must be one line of at most $MAX_BUILD_COMMAND characters", "buildCommand")
    if (framework != null && !frameworkId.matches(framework)) invalid("the framework must be at most 40 lowercase letters, digits and hyphens", "framework")
    return this
}

private fun ApplicationCall.validEnv(vars: List<EnvVar>): List<EnvVar> {
    if (vars.any { !envVarName.matches(it.name) }) invalid("env var names must match [A-Za-z_][A-Za-z0-9_]*", "env")
    if (vars.distinctBy { it.name }.size != vars.size) invalid("env var names must be unique", "env")
    auditDetails("names" to vars.joinToString(",") { it.name })
    return vars
}

private fun String.isRepoPath() = repoPath.matches(this) && ".." !in split('/')

suspend fun App.claimPlatformDomain(scope: ServiceScope) {
    if (scope.service.kind.servesHttp) domains.ensurePlatform(scope)
}
