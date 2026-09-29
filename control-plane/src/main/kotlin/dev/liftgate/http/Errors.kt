package dev.liftgate.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/17/2026
 */
class LiftgateException(val status: HttpStatusCode, val code: String, override val message: String, val field: String? = null) : RuntimeException(message)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class ErrorBody(val error: String, val message: String, @EncodeDefault(EncodeDefault.Mode.NEVER) val field: String? = null)

fun notFound(what: String): Nothing = throw LiftgateException(HttpStatusCode.NotFound, "not_found", "$what not found")

fun forbidden(): Nothing = throw LiftgateException(HttpStatusCode.Forbidden, "forbidden", "insufficient permissions")

fun unauthorized(): Nothing = throw LiftgateException(HttpStatusCode.Unauthorized, "unauthorized", "authentication required")

fun conflict(message: String): Nothing = throw LiftgateException(HttpStatusCode.Conflict, "conflict", message)

fun invalid(message: String, field: String? = null): Nothing = throw LiftgateException(HttpStatusCode.UnprocessableEntity, "invalid", message, field)

fun accountPending(): Nothing = throw LiftgateException(HttpStatusCode.Forbidden, "account_pending", "your account is waiting for approval")

fun orgSuspended(message: String = "this organization is suspended"): Nothing = throw LiftgateException(HttpStatusCode.Forbidden, "org_suspended", message)

fun planLimit(message: String, field: String? = null): Nothing = throw LiftgateException(HttpStatusCode.Conflict, "plan_limit", message, field)

fun githubNotConnected(): Nothing = throw LiftgateException(HttpStatusCode.Conflict, "github_not_connected", "connect GitHub to import a repository")
