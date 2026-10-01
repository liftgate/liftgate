package dev.liftgate.build

import dev.liftgate.service.ServiceSpec
import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 10/1/2026
 */
@Serializable
data class DetectedService(
    val selected: Boolean,
    val framework: Framework?,
    val packageManager: String?,
    val builder: String,
    val spec: ServiceSpec,
    val defaults: Defaults,
    val evidence: String,
    val warnings: List<String>,
    val healthHint: String?,
    val variables: List<DetectedVariable>,
) {
    @Serializable
    data class Framework(val id: String, val name: String)

    @Serializable
    data class Defaults(val build: String?, val start: String?)
}
