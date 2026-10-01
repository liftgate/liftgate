package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.build.Detection
import dev.liftgate.build.Detector
import io.ktor.client.plugins.ResponseException
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

const val DETECTS_PER_MINUTE = 30
private const val MAX_CACHED = 128 * 1024
private val budget = 4.seconds
private val log = LoggerFactory.getLogger(Detector::class.java)

suspend fun App.detect(installationId: Long, repoFullName: String, ref: String, importer: String? = null): Detection {
    val github = github ?: conflict("the GitHub App is not configured")
    var head: Detection.Commit? = null
    val detection = try {
        withTimeoutOrNull(budget) {
            val token = github.installationToken(installationId, repoFullName.substringAfter('/'))
            if (importer?.let { github.canPush(token, repoFullName, it) } == false) {
                return@withTimeoutOrNull unread(repoFullName, "The GitHub account that imported $repoFullName no longer has write access, so nothing was read and builds will fail. Re-import the project.")
            }
            val commit = github.commit(token, repoFullName, ref)
            head = Detection.Commit(commit.sha, commit.message?.lineSequence()?.first())
            val key = "$repoFullName@${commit.sha}"
            cache.detections[key]?.let { runCatching { json.decodeFromString(Detection.serializer(), it) }.getOrNull() } ?: run {
                val (paths, truncated) = github.tree(token, repoFullName, commit.treeSha)
                Detector.detect(repoFullName, paths, github.files(token, repoFullName, commit.sha, Detector.wanted(paths)), truncated).also {
                    val encoded = json.encodeToString(Detection.serializer(), it)
                    if (!it.partial && encoded.length <= MAX_CACHED) cache.detections[key] = encoded
                }
            }
        } ?: unread(repoFullName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("detecting {} at {} failed: {} {}", repoFullName, ref, e::class.simpleName, (e as? ResponseException)?.response?.status ?: "")
        unread(repoFullName)
    }
    return detection.copy(ref = ref, commit = head)
}

fun ApplicationCall.ref(): String? = request.queryParameters["ref"]?.also { if (!refPattern.matches(it) || ".." in it) invalid("ref must be a branch name or commit sha", "ref") }

private fun unread(repoFullName: String, warning: String = "Couldn't read $repoFullName, so nothing was detected. Railpack will still detect the stack during the build.") = Detection(
    ref = "",
    commit = null,
    partial = true,
    services = emptyList(),
    directories = emptyList(),
    warnings = listOf(warning),
)
