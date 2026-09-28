package dev.liftgate.k8s

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.LogWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAIL_LINES = 500
const val MAX_LOG_LINE_BYTES = 16 * 1024

/**
 * @author Dean
 * @date 9/27/2026
 */
class PodLogs(private val kube: KubernetesClient, private val relist: Duration = 10.seconds) {
    private val log = LoggerFactory.getLogger(PodLogs::class.java)

    fun follow(namespace: String, serviceId: UUID, previous: Boolean): Flow<String> = channelFlow {
        val pods = kube.pods().inNamespace(namespace)
        fun list() = pods.withLabel(SERVICE_ID_LABEL, serviceId.toString()).list().items
        if (previous) return@channelFlow list().forEach { pod ->
            runCatching { pods.resource(pod).usingTimestamps().terminated().tailingLines(TAIL_LINES).log }
                .onFailure { log.debug("pod {} has no previous container output", pod.metadata.name, it) }.getOrNull()
                ?.lines()?.filter(String::isNotEmpty)?.forEach { send("${pod.tag} $it") }
        }
        val watches = mutableMapOf<String, LogWatch>()
        try {
            while (true) {
                runCatching {
                    val live = list().filter { it.started }.associateBy { it.key }
                    (watches.keys - live.keys).forEach { watches.remove(it)?.close() }
                    (live - watches.keys).forEach { (key, pod) -> watches[key] = pods.resource(pod).usingTimestamps().tailingLines(TAIL_LINES).watchLog(lines(pod.tag)) }
                }.onFailure { currentCoroutineContext().ensureActive(); log.warn("could not follow the pods of service {}", serviceId, it) }
                delay(relist)
            }
        } finally {
            watches.values.forEach(LogWatch::close)
        }
    }.flowOn(Dispatchers.IO)

    private val Pod.container get() = status?.containerStatuses?.firstOrNull()

    private val Pod.key get() = "${metadata.name}/${container?.restartCount}"

    private val Pod.started get() = container?.state?.let { it.running != null || it.terminated != null } == true

    private val Pod.tag get() = metadata.name.substringAfterLast('-')

    private fun ProducerScope<String>.lines(tag: String) = object : OutputStream() {
        private val line = ByteArrayOutputStream()

        override fun write(b: Int) {
            val newline = b == '\n'.code
            if (newline || line.size() == MAX_LOG_LINE_BYTES) {
                trySendBlocking("$tag ${line.toString(Charsets.UTF_8)}")
                line.reset()
            }
            if (!newline) line.write(b)
        }
    }
}
