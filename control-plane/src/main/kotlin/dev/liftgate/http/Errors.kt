package dev.liftgate.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/17/2026
 */
class LiftgateException(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class ErrorBody(val error: String, val message: String)

fun notFound(what: String): Nothing = throw LiftgateException(HttpStatusCode.NotFound, "not_found", "$what not found")

fun forbidden(): Nothing = throw LiftgateException(HttpStatusCode.Forbidden, "forbidden", "insufficient permissions")

fun unauthorized(): Nothing = throw LiftgateException(HttpStatusCode.Unauthorized, "unauthorized", "authentication required")

fun conflict(message: String): Nothing = throw LiftgateException(HttpStatusCode.Conflict, "conflict", message)

fun invalid(message: String): Nothing = throw LiftgateException(HttpStatusCode.UnprocessableEntity, "invalid", message)
