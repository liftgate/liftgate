package dev.liftgate.service

import dev.liftgate.project.Environment
import dev.liftgate.project.Project
import kotlinx.serialization.Serializable

/**
 * @author Dean
 * @date 9/27/2026
 */
@Serializable
data class ProjectTree(val project: Project, val environments: List<Environment>, val services: List<Service>)
