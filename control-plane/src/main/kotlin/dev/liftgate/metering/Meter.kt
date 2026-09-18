package dev.liftgate.metering

import dev.liftgate.App
import dev.liftgate.db.Services
import dev.liftgate.db.UsageRecords
import dev.liftgate.db.now
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.k8s.MANAGED_LABEL
import dev.liftgate.k8s.ORG_ID_LABEL
import dev.liftgate.k8s.SERVICE_ID_LABEL
import io.fabric8.kubernetes.api.model.Pod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import java.math.RoundingMode
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private const val LOOKBACK = "5m"
private const val TENANT_PODS = "namespace=~\"env-.+\""
private val interval = 60.seconds

/**
 * @author Dean
 * @date 9/17/2026
 */
data class PodOwner(val orgId: UUID, val serviceId: UUID) {
    companion object {
        fun of(pod: Pod): PodOwner? = runCatching {
            PodOwner(UUID.fromString(pod.metadata.labels[ORG_ID_LABEL]), UUID.fromString(pod.metadata.labels[SERVICE_ID_LABEL]))
        }.getOrNull()
    }
}

fun attribute(samples: Map<Map<String, String>, Double>, owners: Map<Pair<String, String>, PodOwner>): Map<PodOwner, Double> =
    samples.entries.mapNotNull { (labels, value) -> owners[labels["namespace"].orEmpty() to labels["pod"].orEmpty()]?.let { it to value } }
        .groupingBy { it.first }
        .fold(0.0) { sum, (_, value) -> sum + value }

/**
 * @author Dean
 * @date 9/17/2026
 */
class Meter(private val app: App, private val prometheus: Prometheus) {
    private val log = LoggerFactory.getLogger(Meter::class.java)

    suspend fun collectOnce(window: Duration) {
        val end = now()
        val start = end.minus(window.toJavaDuration())
        val owners = runInterruptible(Dispatchers.IO) { app.kube.pods().inAnyNamespace().withLabel(MANAGED_LABEL, "true").list().items }
            .mapNotNull { pod -> PodOwner.of(pod)?.let { (pod.metadata.namespace to pod.metadata.name) to it } }
            .toMap()
        val usage = queries(window.inWholeSeconds).flatMap { (metric, promql) ->
            attribute(prometheus.query(promql), owners).map { (owner, quantity) -> Triple(owner, metric, quantity) }
        }
        if (usage.isEmpty()) return
        app.db.tx {
            val known = Services.select(Services.id).where { Services.id inList usage.map { it.first.serviceId }.distinct() }.map { it[Services.id] }.toSet()
            val records = usage.filter { it.first.serviceId in known }
            UsageRecords.batchInsert(records) { (owner, metric, quantity) ->
                this[UsageRecords.orgId] = owner.orgId
                this[UsageRecords.serviceId] = owner.serviceId
                this[UsageRecords.metric] = metric
                this[UsageRecords.quantity] = quantity.toBigDecimal().setScale(6, RoundingMode.HALF_UP)
                this[UsageRecords.windowStart] = start
                this[UsageRecords.windowEnd] = end
            }
            enqueue(Subject.USAGE_RECORDED, buildJsonObject { put("windowStart", start.toString()); put("windowEnd", end.toString()); put("records", records.size) })
        }
    }

    fun start(): Job = app.scope.launch {
        while (true) {
            runCatching { collectOnce(interval) }.onFailure { ensureActive(); log.warn("usage collection failed", it) }
            delay(interval)
        }
    }

    private fun queries(seconds: Long) = mapOf(
        "cpu_seconds" to """sum by (namespace, pod) (rate(container_cpu_usage_seconds_total{$TENANT_PODS,container!=""}[$LOOKBACK])) * $seconds""",
        "memory_byte_seconds" to """sum by (namespace, pod) (avg_over_time(container_memory_working_set_bytes{$TENANT_PODS,container!=""}[$LOOKBACK])) * $seconds""",
        "network_transmit_bytes" to """sum by (namespace, pod) (rate(container_network_transmit_bytes_total{$TENANT_PODS}[$LOOKBACK])) * $seconds""",
    )
}
