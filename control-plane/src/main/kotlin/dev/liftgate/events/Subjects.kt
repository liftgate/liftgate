package dev.liftgate.events

import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
enum class Subject(val value: String) {
    BUILD_REQUESTED("liftgate.build.requested"),
    BUILD_COMPLETED("liftgate.build.completed"),
    RELEASE_REQUESTED("liftgate.release.requested"),
    DEPLOYMENT_UPDATED("liftgate.deployment.updated"),
    DOMAIN_VERIFY_REQUESTED("liftgate.domain.verify.requested"),
    TEARDOWN_REQUESTED("liftgate.teardown.requested"),
    USAGE_RECORDED("liftgate.usage.recorded");

    companion object {
        fun of(value: String) = entries.first { it.value == value }
    }
}

fun buildLogSubject(buildId: UUID) = "liftgate.logs.build.$buildId"

fun serviceLogSubject(serviceId: UUID) = "liftgate.logs.service.$serviceId"
