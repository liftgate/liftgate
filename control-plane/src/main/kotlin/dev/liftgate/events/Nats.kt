package dev.liftgate.events

import dev.liftgate.config.Config
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.Connection
import io.nats.client.ConsumerContext
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val STREAM = "LIFTGATE"
private const val POLL_MILLIS = 5000L

/**
 * @author Dean
 * @date 9/17/2026
 */
class Nats(private val config: Config, private val metrics: MeterRegistry, private val retryDelay: Duration = Duration.ofSeconds(10)) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Nats::class.java)
    private val connection = io.nats.client.Nats.connectReconnectOnConnect(Options.builder().server(config.natsUrl).maxReconnects(-1).build())
    private val jetStream = connection.jetStream()
    private val polls = ConcurrentHashMap<String, Instant>()
    val lastPolls: Map<String, Instant> get() = polls
    val connected get() = connection.status == Connection.Status.CONNECTED
    val logs = LogStream(connection, config)

    fun ensureStream() {
        val management = connection.jetStreamManagement()
        val existing = if (STREAM in management.streamNames) management.getStreamInfo(STREAM).configuration.subjects else null
        val stream = StreamConfiguration.builder()
            .name(STREAM)
            .subjects(Subject.entries.map { it.value }.union(existing.orEmpty()))
            .storageType(StorageType.File)
            .replicas(config.natsReplicas)
            .maxAge(Duration.ofDays(7))
            .duplicateWindow(Duration.ofMinutes(2))
            .build()
        if (existing == null) management.addStream(stream) else management.updateStream(stream)
        logs.ensureStream()
    }

    fun publish(subject: String, id: Long, payload: JsonObject) {
        jetStream.publish(subject, payload.toString().toByteArray(), PublishOptions.builder().messageId(id.toString()).build())
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
        val consumerConfig = ConsumerConfiguration.builder()
            .durable(durable)
            .filterSubject(subject.value)
            .ackPolicy(AckPolicy.Explicit)
            .ackWait(ackWait)
            .maxDeliver(-1)
            .build()
        val permits = Semaphore(concurrency)
        polls[durable] = Instant.now()
        return scope.launch(Dispatchers.IO) {
            var consumer: ConsumerContext? = null
            while (isActive) {
                while (withTimeoutOrNull(POLL_MILLIS) { permits.acquire() } == null) polls[durable] = Instant.now()
                val message = runCatching {
                    val context = consumer ?: connection.getStreamContext(STREAM).createOrUpdateConsumer(consumerConfig).also { consumer = it }
                    context.next(POLL_MILLIS).also { if (it == null) context.getConsumerInfo() }
                }
                    .onSuccess { polls[durable] = Instant.now() }
                    .onFailure { ensureActive(); consumer = null; log.warn("consumer {} failed to fetch", durable, it); delay(1000) }
                    .getOrNull()
                when {
                    message == null -> permits.release()
                    isActive -> launch {
                        try {
                            metrics.counter("liftgate.messages", "consumer", durable, "outcome", deliver(message, ackWait, handler)).increment()
                        } finally {
                            permits.release()
                        }
                    }
                    else -> message.nak()
                }
            }
        }
    }

    fun backlog(durable: String): Long = connection.jetStreamManagement().getConsumers(STREAM)
        .filter { it.name == durable }
        .sumOf { it.numPending + it.numAckPending }

    override fun close() = connection.close()

    private suspend fun deliver(message: Message, ackWait: Duration, handler: suspend (JsonObject) -> Unit): String {
        val payload = runCatching { Json.parseToJsonElement(String(message.data)).jsonObject }.getOrElse {
            log.warn("terminating unparseable message on {}", message.subject, it)
            message.term()
            return "terminated"
        }
        try {
            coroutineScope {
                val heartbeat = launch {
                    while (true) {
                        delay(ackWait.toMillis() / 3)
                        message.inProgress()
                    }
                }
                handler(payload)
                heartbeat.cancel()
            }
            message.ack()
            return "acked"
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) {
                message.nak()
                throw e
            }
            if (e is Redeliver) {
                message.nakWithDelay(e.delay)
                return "deferred"
            }
            log.warn("handler failed for {}", message.subject, e)
            val deliveries = message.metaData().deliveredCount()
            message.nakWithDelay(retryDelay.multipliedBy(1L shl (deliveries - 1).coerceAtMost(6).toInt()).coerceAtMost(Duration.ofMinutes(10)))
            return "failed"
        }
    }
}
