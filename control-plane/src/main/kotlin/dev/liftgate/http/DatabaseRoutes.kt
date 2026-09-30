package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.auth.OrgRole
import dev.liftgate.database.DatabaseScope
import dev.liftgate.database.DatabaseSpec
import dev.liftgate.database.ServiceLink
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
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val MIN_DATABASE_CPU_MILLIS = 100
private const val MIN_DATABASE_MEMORY_MB = 256

/**
 * @author Dean
 * @date 9/30/2026
 */
@Serializable
private data class Restore(val slug: String, @Serializable(with = InstantSerializer::class) val pointInTime: Instant)

fun Route.databaseRoutes(app: App) {
    route("/environments/{id}/databases") {
        get {
            val environment = call.environment(app)
            val databases = app.databases.forEnvironment(environment.id)
            val ready = if (databases.isEmpty()) emptySet() else app.databaseClusters.ready(environment.namespace)
            call.respond(databases.map { it.copy(ready = it.slug in ready) })
        }
        post {
            val environment = call.environment(app, OrgRole.ADMIN)
            call.respond(HttpStatusCode.Created, app.databases.create(environment.id, call.receive<DatabaseSpec>().validated()))
        }
    }
    route("/databases/{id}") {
        delete {
            app.databases.delete(call.database(app, OrgRole.ADMIN).database.id)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/connection") {
            val scope = call.database(app, OrgRole.ADMIN)
            call.respond(mapOf("uri" to (app.databaseClusters.uri(scope.namespace, scope.database.slug) ?: conflict("the database is still starting"))))
        }
        get("/backups") {
            val scope = call.database(app)
            call.respond(app.databaseClusters.backups(scope.namespace, scope.database.slug))
        }
        post("/restore") {
            val scope = call.database(app, OrgRole.ADMIN)
            if (app.config.databaseBackup == null) conflict("database backups are not configured on this installation")
            val body = call.receive<Restore>()
            val target = body.pointInTime.truncatedTo(ChronoUnit.SECONDS)
            if (!target.isBefore(Instant.now())) invalid("the point in time must be in the past", "pointInTime")
            if (app.databaseClusters.backups(scope.namespace, scope.database.slug).none { it.phase == "completed" && it.stoppedAt?.let(Instant::parse)?.isBefore(target) == true }) {
                invalid("pick a point in time after the first completed backup", "pointInTime")
            }
            val spec = scope.database.run { DatabaseSpec(body.slug, storageGb, cpuMillis, memoryMb) }.validated()
            call.respond(HttpStatusCode.Created, app.databases.create(scope.database.environmentId, spec, scope.database.id, target))
        }
        route("/links") {
            post {
                val scope = call.database(app, OrgRole.ADMIN)
                val link = call.receive<ServiceLink>()
                if (!envVarName.matches(link.envName) || link.envName == "PORT") invalid("the variable name must match [A-Za-z_][A-Za-z0-9_]* and cannot be PORT", "envName")
                if (call.service(app, OrgRole.ADMIN, link.serviceId).service.environmentId != scope.database.environmentId) invalid("link a service in the same environment", "serviceId")
                app.databases.link(scope.database.id, link)
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/{serviceId}") {
                app.databases.unlink(call.database(app, OrgRole.ADMIN).database.id, call.uuid("serviceId"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

suspend fun ApplicationCall.database(app: App, min: OrgRole = OrgRole.MEMBER): DatabaseScope {
    val scope = app.databases.scope(uuid("id")) ?: notFound("database")
    authorize(app, scope.org.id, min)
    return scope
}

private fun DatabaseSpec.validated(): DatabaseSpec {
    requireSlug(slug)
    if (!slug.first().isLetter()) invalid("a database slug must start with a letter", "slug")
    if (storageGb !in 1..MAX_STORAGE_GB) invalid("storage must be between 1 and $MAX_STORAGE_GB GB", "storageGb")
    if (cpuMillis !in MIN_DATABASE_CPU_MILLIS..MAX_CPU_MILLIS) invalid("CPU must be between $MIN_DATABASE_CPU_MILLIS and $MAX_CPU_MILLIS millicores", "cpuMillis")
    if (memoryMb !in MIN_DATABASE_MEMORY_MB..MAX_MEMORY_MB) invalid("memory must be between $MIN_DATABASE_MEMORY_MB and $MAX_MEMORY_MB MB", "memoryMb")
    return this
}
