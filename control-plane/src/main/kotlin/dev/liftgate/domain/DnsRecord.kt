package dev.liftgate.domain

import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
data class DnsRecord(val type: String, val name: String, val value: String)
