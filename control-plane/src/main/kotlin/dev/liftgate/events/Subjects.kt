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
    USAGE_RECORDED("liftgate.usage.recorded"),
    ORG_SUSPENDED("liftgate.org.suspended"),
    ORG_UNSUSPENDED("liftgate.org.unsuspended"),
    USER_UPDATED("liftgate.user.updated"),
    ORG_PLAN_CHANGED("liftgate.org.plan.changed"),
    NOTIFICATION_REQUESTED("liftgate.notification.requested"),
    DATABASE_REQUESTED("liftgate.database.requested"),
}

fun buildLogSubject(buildId: UUID) = "liftgate.logs.build.$buildId"
