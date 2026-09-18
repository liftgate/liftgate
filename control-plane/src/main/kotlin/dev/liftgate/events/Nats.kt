package dev.liftgate.events

import dev.liftgate.config.Config
import io.nats.client.Message
import io.nats.client.Options
import io.nats.client.PublishOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Duration

private const val STREAM = "LIFTGATE"

/**
 * @author Dean
 * @date 9/17/2026
 */
class Nats(config: Config) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Nats::class.java)
    private val connection = io.nats.client.Nats.connect(Options.builder().server(config.natsUrl).maxReconnects(-1).build())
    private val jetStream = connection.jetStream()

    fun ensureStream() {
        val management = connection.jetStreamManagement()
        val stream = StreamConfiguration.builder()
            .name(STREAM)
            .subjects(Subject.entries.map { it.value })
            .storageType(StorageType.File)
            .build()
        if (STREAM in management.streamNames) management.updateStream(stream) else management.addStream(stream)
    }

    fun publish(subject: Subject, id: Long, payload: JsonObject) {
        jetStream.publish(subject.value, payload.toString().toByteArray(), PublishOptions.builder().messageId(id.toString()).build())
    }

    fun consume(
        subject: Subject,
        durable: String,
        scope: CoroutineScope,
        ackWait: Duration = Duration.ofSeconds(30),
        concurrency: Int = 1,
        handler: suspend (JsonObject) -> Unit,
    ): Job {
        require('.' !in durable) { "durable consumer names cannot contain dots: $durable" }
        val consumer = connection.getStreamContext(STREAM).createOrUpdateConsumer(
            ConsumerConfiguration.builder()
                .durable(durable)
                .filterSubject(subject.value)
                .ackPolicy(AckPolicy.Explicit)
                .ackWait(ackWait)
                .maxDeliver(5)
                .build(),
        )
        val permits = Semaphore(concurrency)
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                permits.acquire()
                val message = runCatching { consumer.next(Duration.ofSeconds(5)) }
                    .onFailure { ensureActive(); log.warn("consumer {} failed to fetch", durable, it); delay(1000) }
                    .getOrNull()
                if (message == null) permits.release() else launch { try { deliver(message, handler) } finally { permits.release() } }
            }
        }
    }

    fun publishLog(subject: String, line: String) = connection.publish(subject, line.toByteArray())

    fun logs(subject: String): Flow<String> = callbackFlow {
        val dispatcher = connection.createDispatcher { trySend(String(it.data)) }
        dispatcher.subscribe(subject)
        awaitClose { connection.closeDispatcher(dispatcher) }
    }

    override fun close() = connection.close()

    private suspend fun deliver(message: Message, handler: suspend (JsonObject) -> Unit) = try {
        handler(Json.parseToJsonElement(String(message.data)).jsonObject)
        message.ack()
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        log.warn("handler failed for {}", message.subject, e)
        message.nakWithDelay(Duration.ofSeconds(10))
    }
}
