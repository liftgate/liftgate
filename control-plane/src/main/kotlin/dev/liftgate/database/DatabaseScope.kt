package dev.liftgate.database

import dev.liftgate.k8s.Tenancy
import dev.liftgate.org.Organization
import dev.liftgate.org.Plan
import dev.liftgate.project.Environment
import dev.liftgate.project.Project

/**
 * @author Dean
 * @date 9/30/2026
 */
data class DatabaseScope(
    val database: Database,
    override val environment: Environment,
    override val project: Project,
    override val org: Organization,
    override val plan: Plan = Plan(),
) : Tenancy
