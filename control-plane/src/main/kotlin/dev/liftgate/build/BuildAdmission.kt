package dev.liftgate.build

import dev.liftgate.db.Builds
import dev.liftgate.db.Db
import dev.liftgate.db.Organizations
import dev.liftgate.db.now
import dev.liftgate.db.sql
import dev.liftgate.deploy.Build
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.events.Redeliver
import dev.liftgate.org.Plans
import dev.liftgate.service.orgServiceIds
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.util.UUID

private val busyRetry = Duration.ofSeconds(15)

/**
 * @author Dean
 * @date 9/27/2026
 */
class BuildAdmission(private val db: Db, private val plans: Plans) {
    suspend fun admit(build: Build, orgId: UUID): String? = db.tx {
        val org = Organizations.select(Organizations.plan, Organizations.suspendedAt).where { Organizations.id eq orgId }.forUpdate().single()
        val name = plans.name(org[Organizations.plan])
        val plan = plans.of(name)
        fun others(filter: Op<Boolean>) =
            Builds.selectAll().where { (Builds.serviceId inSubQuery orgServiceIds(orgId)) and (Builds.id neq build.id) and filter }.count()
        when {
            org[Organizations.suspendedAt] != null -> "the organization is suspended"
            build.status == BuildStatus.RUNNING -> null
            plan.concurrentBuilds?.let { others(Builds.status eq BuildStatus.RUNNING.sql) >= it } == true -> throw Redeliver(busyRetry)
            plan.buildsPerHour?.let { others(Builds.startedAt greaterEq now().minusHours(1)) >= it } == true ->
                "the $name plan's builds per hour limit is ${plan.buildsPerHour}"
            else -> {
                Builds.update({ Builds.id eq build.id }) {
                    it[status] = BuildStatus.RUNNING.sql
                    it[startedAt] = now()
                }
                null
            }
        }
    }
}
