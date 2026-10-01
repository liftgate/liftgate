package dev.liftgate.build

import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 10/1/2026
 */
@Serializable
data class DetectedVariable(val name: String, val description: String?, val source: String, val required: Boolean, val secretHint: Boolean)
