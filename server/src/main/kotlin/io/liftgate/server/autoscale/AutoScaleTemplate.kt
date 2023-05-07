package io.liftgate.server.autoscale

import kotlinx.serialization.Serializable

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
@Serializable
data class AutoScaleTemplate(
    val group: String,
    val template: String,
    val scaleUpMax: Int,
    val minimumReplicas: Int,
    val autoStart: Boolean,
    val availabilityStrategy: String,
    val propertyChoiceScheme: String,
    val desiredMetricRatio: Double = 50.0,
    val metricRatioThreshold: Double = 10.0,
)
{
    var startedAutoScale = false
}
