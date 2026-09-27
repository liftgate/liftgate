package dev.liftgate.events

import dev.liftgate.config.Config
import io.nats.client.Connection
import io.nats.client.api.DeliverPolicy
import io.nats.client.api.OrderedConsumerConfiguration
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import io.nats.client.impl.Headers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.time.Duration
import java.util.UUID

private const val STREAM = "LIFTGATE_LOGS"
private const val END = "Liftgate-End"

/**
 * @author Dean
 * @date 9/27/2026
 */
class LogStream(private val connection: Connection, private val config: Config) {
    private val jetStream = connection.jetStream()

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

    fun follow(buildId: UUID, finished: Boolean): Flow<String> = callbackFlow {
        val subject = buildLogSubject(buildId)
        val stream = connection.getStreamContext(STREAM)
        if (finished && runCatching { stream.getLastMessage(subject) }.isFailure) {
            close()
            return@callbackFlow
        }
        val dispatcher = connection.createDispatcher()
        try {
            val consumer = stream.createOrderedConsumer(OrderedConsumerConfiguration().filterSubject(subject).deliverPolicy(DeliverPolicy.All))
                .consume(dispatcher) { message ->
                    trySendBlocking(String(message.data))
                    if (message.headers?.containsKey(END) == true || finished && message.metaData().pendingCount() == 0L) close()
                }
            awaitClose { consumer.close() }
        } finally {
            connection.closeDispatcher(dispatcher)
        }
    }.flowOn(Dispatchers.IO)
}
