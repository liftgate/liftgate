package dev.liftgate.service

import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/30/2026
 */
@Serializable
data class Volume(val mountPath: String, val sizeGb: Int)
