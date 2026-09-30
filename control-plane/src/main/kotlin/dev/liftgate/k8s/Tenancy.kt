package dev.liftgate.k8s

import dev.liftgate.org.Organization
import dev.liftgate.org.Plan
import dev.liftgate.project.Environment
import dev.liftgate.project.Project

/**
 * @author Dean
 * @date 9/30/2026
 */
interface Tenancy {
    val environment: Environment
    val project: Project
    val org: Organization
    val plan: Plan
    val namespace: String get() = environment.namespace
    val suspended: Boolean get() = org.suspendedAt != null
}
