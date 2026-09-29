package dev.liftgate.build

import dev.liftgate.App
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC = "HmacSHA256"
private const val BRANCH_PREFIX = "refs/heads/"
private const val MAX_COMMITS = 2048
private const val MAX_FILES_PER_COMMIT = 3000
private const val MAX_MATCH_STEPS = 4_000_000L

/**
 * @author Dean
 * @date 9/17/2026
 */
object Webhooks {
    fun sign(secret: String, body: ByteArray): String =
        "sha256=" + Mac.getInstance(HMAC).apply { init(SecretKeySpec(secret.toByteArray(), HMAC)) }.doFinal(body).joinToString("") { "%02x".format(it) }

    fun verify(secret: String, body: ByteArray, signatureHeader: String?): Boolean =
        signatureHeader != null && MessageDigest.isEqual(sign(secret, body).toByteArray(), signatureHeader.toByteArray())
}

/**
 * @author Dean
 * @date 9/17/2026
 */
class WebhookHandler(private val app: App) {
    suspend fun handlePush(payload: JsonObject, admit: (Long) -> Unit) {
        val ref = payload.text("ref")?.takeIf { it.startsWith(BRANCH_PREFIX) } ?: return
        if (payload.flag("deleted")) return
        val branch = ref.removePrefix(BRANCH_PREFIX)
        val sha = payload.text("after") ?: return
        val repo = (payload["repository"] as? JsonObject)?.text("full_name") ?: return
        val installation = (payload["installation"] as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull ?: return
        val message = (payload["head_commit"] as? JsonObject)?.text("message")
        val commits = (payload["commits"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val changes = commits.map { commit -> listOf("added", "modified", "removed").flatMap { (commit[it] as? JsonArray).orEmpty() } }
        val files = changes.flatten().map { it.jsonPrimitive.content }.toSet()
        val services = app.projects.environmentsForRepo(installation, repo, branch)
            .flatMap { app.services.forEnvironment(it.id) }
            .ifEmpty { return }
        admit(installation)
        val steps = files.sumOf { it.length + 1L } * services.sumOf { service -> service.watchPaths.sumOf { it.length + 1L } + 1 }
        val buildAll = payload.flag("created") || payload.flag("forced") || commits.isEmpty() || commits.size >= MAX_COMMITS ||
            changes.any { it.size >= MAX_FILES_PER_COMMIT } || steps > MAX_MATCH_STEPS
        services.filter { buildAll || it.spec().watches(files) }.forEach { app.builds.request(it.id, sha, message, branch) }
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
}
