package dev.liftgate.config

/**
 * @author Dean
 * @date 9/30/2026
 */
data class DatabaseBackupConfig(
    val destinationPath: String,
    val endpointUrl: String?,
    val accessKeyId: String?,
    val secretAccessKey: String?,
    val region: String,
    val retention: String,
    val schedule: String,
    val egress: List<Pair<String, Int>>,
)
