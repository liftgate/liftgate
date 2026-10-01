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
private val budget = 4.seconds
private val log = LoggerFactory.getLogger(Detector::class.java)

suspend fun App.detect(installationId: Long, repoFullName: String, ref: String): Detection {
    val github = github ?: conflict("the GitHub App is not configured")
    var head: Detection.Commit? = null
    var listing: Pair<List<String>, Boolean>? = null
    var files = emptyMap<String, String>()
    val detection = try {
        withTimeoutOrNull(budget) {
            val token = github.installationToken(installationId, repoFullName.substringAfter('/'))
            val commit = github.commit(token, repoFullName, ref)
            head = Detection.Commit(commit.sha, commit.message?.lineSequence()?.first())
            val key = "$repoFullName@${commit.sha}"
            cache.detections[key]?.let { runCatching { json.decodeFromString(Detection.serializer(), it) }.getOrNull() } ?: run {
                val (paths, truncated) = github.tree(token, repoFullName, commit.treeSha).also { listing = it }
                files = github.files(token, repoFullName, commit.sha, Detector.wanted(paths))
                Detector.detect(repoFullName, paths, files, truncated).also { if (!it.partial) cache.detections[key] = json.encodeToString(Detection.serializer(), it) }
            }
        } ?: listing?.let { (paths, truncated) -> Detector.detect(repoFullName, paths, files, truncated).copy(partial = true) } ?: unread(repoFullName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("detecting {} at {} failed: {} {}", repoFullName, ref, e::class.simpleName, (e as? ResponseException)?.response?.status ?: "")
        unread(repoFullName)
    }
    return detection.copy(ref = ref, commit = head)
}

fun ApplicationCall.ref(): String? = request.queryParameters["ref"]?.also { if (!refPattern.matches(it) || ".." in it) invalid("ref must be a branch name or commit sha", "ref") }

private fun unread(repoFullName: String) = Detection(
    ref = "",
    commit = null,
    partial = true,
    services = emptyList(),
    directories = emptyList(),
    warnings = listOf("Couldn't read $repoFullName, so nothing was detected. Railpack will still detect the stack during the build."),
)
