package dev.liftgate.admin

import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/30/2026
 */
@Serializable
data class OperatorSummary(val pending: Long, val plans: List<String>)
