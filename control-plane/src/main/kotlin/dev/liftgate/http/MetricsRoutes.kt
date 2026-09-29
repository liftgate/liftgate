package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.k8s.SERVICE_ID_LABEL
import dev.liftgate.metering.ServiceMetrics
import io.fabric8.kubernetes.client.KubernetesClientException
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private const val POINTS = 300
private const val MIB = 1024L * 1024
private val ranges = mapOf("1h" to 1.hours, "6h" to 6.hours, "24h" to 24.hours, "7d" to 7.days)
private val shortestRateWindow = 5.minutes

fun Route.metricsRoutes(app: App) {
    get("/services/{id}/metrics") {
        val scope = call.service(app)
        val range = ranges[call.request.queryParameters["range"] ?: "1h"] ?: invalid("range must be one of ${ranges.keys.joinToString()}")
        val step = range.inWholeSeconds / POINTS
        val end = Instant.now().epochSecond
        val start = end - range.inWholeSeconds + step
        val namespace = scope.environment.namespace
        val pods = """max by (namespace, pod) (kube_pod_labels{namespace="$namespace", label_liftgate_dev_service_id="${scope.service.id}"})"""
        val window = "${maxOf(step, shortestRateWindow.inWholeSeconds)}s"
        val metrics = coroutineScope {
            fun series(aggregate: String, perPod: String) = async {
                app.prometheus.range("$aggregate ($perPod * on (namespace, pod) group_left () $pods)", start, end, step).values.singleOrNull().orEmpty()
            }
            fun network(direction: String) = series("sum", """max by (namespace, pod, interface) (rate(container_network_${direction}_bytes_total{namespace="$namespace", pod!=""}[$window]))""")
            val cpu = series("sum", """max by (namespace, pod) (rate(container_cpu_usage_seconds_total{namespace="$namespace", container="", pod!=""}[$window]))""")
            val memory = series("max", """max by (namespace, pod) (container_memory_working_set_bytes{namespace="$namespace", container="", pod!=""})""")
            val rx = network("receive")
            val tx = network("transmit")
            val restarts = async {
                runInterruptible(Dispatchers.IO) {
                    try {
                        app.kube.pods().inNamespace(namespace).withLabel(SERVICE_ID_LABEL, scope.service.id.toString()).list().items
                    } catch (e: KubernetesClientException) {
                        if (e.code != HttpStatusCode.Forbidden.value) throw e
                        emptyList()
                    }
                }.sumOf { pod -> pod.status?.containerStatuses.orEmpty().sumOf { it.restartCount ?: 0 } }
            }
            ServiceMetrics(start, end, step, cpu.await(), memory.await(), (app.deployments.current(scope.service.id)?.config?.memoryMb ?: scope.service.memoryMb) * MIB, rx.await(), tx.await(), restarts.await())
        }
        call.respond(metrics)
    }
}
