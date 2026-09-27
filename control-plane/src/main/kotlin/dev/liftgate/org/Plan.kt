package dev.liftgate.org

import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
data class Plan(
    val ownedOrgs: Int? = null,
    val projects: Int? = null,
    val environmentsPerProject: Int? = null,
    val services: Int? = null,
    val cpuMillis: Int? = null,
    val memoryMb: Int? = null,
    val replicas: Int? = null,
    val cpuRequestRatio: Double = 1.0,
    val ephemeralMb: Int = 2048,
    val customDomains: Int? = null,
    val concurrentBuilds: Int? = null,
    val buildsPerHour: Int? = null,
    val egressBandwidth: String? = null,
    val udp: Boolean = true,
) {
    init {
        require(listOfNotNull(ownedOrgs, projects, environmentsPerProject, services, cpuMillis, memoryMb, replicas, customDomains, concurrentBuilds, buildsPerHour).all { it >= 0 }) {
            "plan limits cannot be negative"
        }
        require(concurrentBuilds == null || buildsPerHour != null) { "a plan that limits concurrentBuilds must also limit buildsPerHour" }
        require(cpuRequestRatio > 0 && cpuRequestRatio <= 1) { "cpuRequestRatio must be above 0 and at most 1" }
        require(ephemeralMb > 0) { "ephemeralMb must be positive" }
        require(egressBandwidth?.matches(Regex("[0-9]+[kMG]?")) != false) { "egressBandwidth must be a rate such as 20M" }
    }
}
