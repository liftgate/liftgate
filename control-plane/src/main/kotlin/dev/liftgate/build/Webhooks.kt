package dev.liftgate.build

import dev.liftgate.App
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

/**
 * @author Dean
 * @date 9/17/2026
 */
object Webhooks {
    fun verify(secret: String, body: ByteArray, signatureHeader: String?): Boolean {
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(secret.toByteArray(), HMAC)) }
        val expected = "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
        return signatureHeader != null && MessageDigest.isEqual(expected.toByteArray(), signatureHeader.toByteArray())
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
class WebhookHandler(private val app: App) {
    suspend fun handlePush(payload: JsonObject) {
        val ref = payload.text("ref")?.takeIf { it.startsWith(BRANCH_PREFIX) } ?: return
        if (payload["deleted"]?.jsonPrimitive?.booleanOrNull == true) return
        val branch = ref.removePrefix(BRANCH_PREFIX)
        val sha = payload.text("after") ?: return
        val repo = (payload["repository"] as? JsonObject)?.text("full_name") ?: return
        val installation = (payload["installation"] as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull ?: return
        val message = (payload["head_commit"] as? JsonObject)?.text("message")
        app.projects.environmentsForRepo(installation, repo, branch)
            .flatMap { app.services.forEnvironment(it.id) }
            .forEach { app.builds.request(it.id, sha, message, branch) }
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
}
