@file:UseSerializers(UuidSerializer::class)

package dev.liftgate.project

import dev.liftgate.http.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class Project(
    val id: UUID,
    val orgId: UUID,
    val slug: String,
    val name: String,
    val repoFullName: String,
    val repoDefaultBranch: String,
    val installationId: Long,
)

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
enum class EnvironmentKind {
    @SerialName("production") PRODUCTION,
    @SerialName("preview") PREVIEW,
}

/**
 * @author Dean
 * @date 9/17/2026
 */
@Serializable
data class Environment(
    val id: UUID,
    val projectId: UUID,
    val slug: String,
    val name: String,
    val kind: EnvironmentKind,
    val branch: String,
    val namespace: String,
)
