package dev.liftgate.build

import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 10/1/2026
 */
@Serializable
data class Detection(
    val ref: String,
    val commit: Commit?,
    val partial: Boolean,
    val services: List<DetectedService>,
    val directories: List<Directory>,
    val warnings: List<String>,
) {
    @Serializable
    data class Commit(val sha: String, val message: String?)

    @Serializable
    data class Directory(val path: String, val framework: String?)
}
