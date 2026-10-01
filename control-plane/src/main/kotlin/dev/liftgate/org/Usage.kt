package dev.liftgate.org

import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
data class Usage(
    val plan: String,
    val limits: Plan,
    val projects: Int,
    val services: Int,
    val customDomains: Int,
    val replicas: Int,
    val cpuMillis: Int,
    val memoryMb: Int,
    val storageGb: Int,
)
