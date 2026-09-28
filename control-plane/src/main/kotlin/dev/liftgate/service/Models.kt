@file:UseSerializers(UuidSerializer::class)

package dev.liftgate.service

import dev.liftgate.http.UuidSerializer
import dev.liftgate.org.Organization
import dev.liftgate.project.Environment
import dev.liftgate.project.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
enum class ServiceKind(val servesHttp: Boolean) {
    @SerialName("web") WEB(true),
    @SerialName("worker") WORKER(false),
    @SerialName("cron") CRON(false),
    @SerialName("static") STATIC(true),
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
    val healthCheckPath: String? = null,
    val watchPaths: List<String> = emptyList(),
    val internalHost: String? = null,
) {
    val listens get() = port != null || kind.servesHttp

    fun spec() = ServiceSpec(slug, name, kind, rootDir, buildStrategy, dockerfilePath, port, replicas, cpuMillis, memoryMb, cronSchedule, startCommand, healthCheckPath, watchPaths)
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
    val healthCheckPath: String? = null,
    val watchPaths: List<String> = emptyList(),
) {
    fun service(id: UUID, environmentId: UUID) =
        Service(id, environmentId, slug, name, kind, rootDir, buildStrategy, dockerfilePath, port, replicas, cpuMillis, memoryMb, cronSchedule, startCommand, healthCheckPath, watchPaths)

    fun watches(files: Collection<String>): Boolean {
        val globs = watchPaths.ifEmpty { listOfNotNull(rootDir.trim('/').takeIf { it.isNotEmpty() }?.let { "$it/**" }) }
            .map { FileSystems.getDefault().getPathMatcher("glob:${it.trimStart('/')}") }
        return globs.isEmpty() || files.any { file -> globs.any { it.matches(Path.of(file)) } }
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
data class ServiceScope(val service: Service, val environment: Environment, val project: Project, val org: Organization) {
    fun buildUrl(dashboardUrl: String, buildId: UUID) = "$dashboardUrl/${org.slug}/${project.slug}/${environment.slug}/${service.slug}?tab=builds&build=$buildId"
}

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class EnvVar(val name: String, val value: String?, val secret: Boolean = false)
