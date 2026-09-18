@file:UseSerializers(UuidSerializer::class)

package dev.liftgate.service

import dev.liftgate.http.UuidSerializer
import dev.liftgate.org.Organization
import dev.liftgate.project.Environment
import dev.liftgate.project.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
enum class ServiceKind {
    @SerialName("web") WEB,
    @SerialName("worker") WORKER,
    @SerialName("cron") CRON,
    @SerialName("static") STATIC,
}

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
enum class BuildStrategy {
    @SerialName("auto") AUTO,
    @SerialName("dockerfile") DOCKERFILE,
}

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class Service(
    val id: UUID,
    val environmentId: UUID,
    val slug: String,
    val name: String,
    val kind: ServiceKind,
    val rootDir: String,
    val buildStrategy: BuildStrategy,
    val dockerfilePath: String,
    val port: Int?,
    val replicas: Int,
    val cpuMillis: Int,
    val memoryMb: Int,
    val cronSchedule: String?,
    val startCommand: String?,
) {
    fun spec() = ServiceSpec(slug, name, kind, rootDir, buildStrategy, dockerfilePath, port, replicas, cpuMillis, memoryMb, cronSchedule, startCommand)
}

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class ServiceSpec(
    val slug: String,
    val name: String,
    val kind: ServiceKind,
    val rootDir: String = "/",
    val buildStrategy: BuildStrategy = BuildStrategy.AUTO,
    val dockerfilePath: String = "Dockerfile",
    val port: Int? = null,
    val replicas: Int = 1,
    val cpuMillis: Int = 500,
    val memoryMb: Int = 512,
    val cronSchedule: String? = null,
    val startCommand: String? = null,
)

/**
 * @author Dean
 * @date 9/17/2026
 */
data class ServiceScope(val service: Service, val environment: Environment, val project: Project, val org: Organization)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class EnvVar(val name: String, val value: String?, val secret: Boolean = false)
