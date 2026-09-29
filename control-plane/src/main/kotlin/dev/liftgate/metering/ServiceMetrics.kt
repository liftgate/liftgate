package dev.liftgate.metering

import dev.liftgate.metering.Prometheus.Point
import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
data class ServiceMetrics(
    val start: Long,
    val end: Long,
    val step: Long,
    val cpu: List<Point>,
    val memory: List<Point>,
    val memoryLimitBytes: Long,
    val networkRx: List<Point>,
    val networkTx: List<Point>,
    val restarts: Int,
)
