package dev.liftgate.database

import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/30/2026
 */
@Serializable
data class DatabaseSpec(val slug: String, val storageGb: Int = 1, val cpuMillis: Int = 500, val memoryMb: Int = 512)
