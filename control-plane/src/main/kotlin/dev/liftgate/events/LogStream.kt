package dev.liftgate.events

import dev.liftgate.config.Config
import io.nats.client.Connection
import io.nats.client.Message
import io.nats.client.api.DeliverPolicy
import io.nats.client.api.OrderedConsumerConfiguration
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import io.nats.client.impl.Headers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.UUID

private const val STREAM = "LIFTGATE_LOGS"
private const val END = "Liftgate-End"
private const val PAGE = 1_000

/**
 * @author Dean
 * @date 9/27/2026
 */
class LogStream(private val connection: Connection, private val config: Config) {
    private val jetStream = connection.jetStream()
    private val dispatcher by lazy { connection.createDispatcher() }

    fun ensureStream() {
        val management = connection.jetStreamManagement()
        val stream = StreamConfiguration.builder()
            .name(STREAM)
            .subjects("liftgate.logs.build.>")
            .storageType(StorageType.File)
            .replicas(config.natsReplicas)
            .maxAge(Duration.ofDays(7))
            .maxMessagesPerSubject(50_000)
            .maxBytes(config.buildLogsMaxBytes)
            .build()
        if (STREAM in management.streamNames) management.updateStream(stream) else management.addStream(stream)
    }

    fun publish(buildId: UUID, line: String) = jetStream.publishAsync(buildLogSubject(buildId), line.toByteArray())

    fun end(buildId: UUID, failure: String?) =
        jetStream.publishAsync(buildLogSubject(buildId), Headers().put(END, "true"), (failure?.let { "Build failed: $it" } ?: "Build succeeded").toByteArray())

    fun follow(buildId: UUID, recheck: Duration = Duration.ofSeconds(30), finished: suspend () -> Boolean): Flow<String> = channelFlow {
        val subject = buildLogSubject(buildId)
        val stream = connection.getStreamContext(STREAM)
        val done = finished()
        if (done && runCatching { stream.getLastMessage(subject) }.isFailure) return@channelFlow
        val reader = launch {
            var from = 1L
            while (true) {
                val page = Channel<Message>(PAGE)
                val consumer = stream.createOrderedConsumer(OrderedConsumerConfiguration().filterSubject(subject).deliverPolicy(DeliverPolicy.ByStartSequence).startSequence(from))
                    .consume(dispatcher) { if (page.trySend(it).isFailure) page.close() }
                page.invokeOnClose { consumer.stop() }
                try {
                    for (message in page) {
                        send(String(message.data))
                        if (message.headers?.containsKey(END) == true || done && message.metaData().pendingCount() == 0L) return@launch
                        from = message.metaData().streamSequence() + 1
                    }
                } finally {
                    consumer.close()
                }
            }
        }
        if (!done) launch {
            while (!finished()) delay(recheck.toMillis())
            delay(recheck.toMillis())
            reader.cancel()
        }.let { watcher -> reader.invokeOnCompletion { watcher.cancel() } }
    }.flowOn(Dispatchers.IO)
}
