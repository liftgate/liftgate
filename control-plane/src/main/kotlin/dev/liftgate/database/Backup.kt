package dev.liftgate.database

import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/30/2026
 */
@Serializable
data class Backup(val name: String, val phase: String?, val startedAt: String?, val stoppedAt: String?)
