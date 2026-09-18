package dev.liftgate.build

import dev.liftgate.db.sql
import dev.liftgate.deploy.Build
import dev.liftgate.k8s.MANAGED_LABEL
import dev.liftgate.org.Organization
import dev.liftgate.project.Project
import dev.liftgate.service.Service
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import java.util.UUID

const val BUILD_LABEL = "liftgate.dev/build"
const val REGISTRY_SECRET = "registry-credentials"

/**
 * @author Dean
 * @date 9/17/2026
 */
data class BuildJobSpec(
    val build: Build,
    val service: Service,
    val project: Project,
    val installationToken: String,
    val imageRef: String,
    val cacheRef: String,
    val buildImage: String,
    val namespace: String,
)

/**
 * @author Dean
 * @date 9/17/2026
 */
object BuildJobs {
    private const val TIMEOUT_SECONDS = 30 * 60L
    private const val RETENTION_SECONDS = 24 * 60 * 60
    private const val BUILDER_UID = 1000L
    private const val DOCKER_CONFIG = "/home/user/.docker"
    private const val TOKEN_KEY = "token"

    fun name(buildId: UUID) = "build-$buildId"

    fun imageRef(registry: String, org: Organization, project: Project, service: Service, sha: String) =
        "$registry/${org.slug}/${project.slug}-${service.slug}:${sha.replace('/', '-')}"

    fun job(spec: BuildJobSpec): Job {
        val labels = mapOf(MANAGED_LABEL to "true", BUILD_LABEL to spec.build.id.toString())
        val env = mapOf(
            "LIFTGATE_REPO_URL" to "https://github.com/${spec.project.repoFullName}.git",
            "LIFTGATE_COMMIT" to spec.build.commitSha,
            "LIFTGATE_ROOT_DIR" to spec.service.rootDir,
            "LIFTGATE_BUILD_STRATEGY" to spec.service.buildStrategy.sql,
            "LIFTGATE_DOCKERFILE_PATH" to spec.service.dockerfilePath,
            "IMAGE" to spec.imageRef,
            "CACHE" to spec.cacheRef,
            "DOCKER_CONFIG" to DOCKER_CONFIG,
        )
        val token = EnvVarBuilder().withName("LIFTGATE_GIT_TOKEN")
            .withNewValueFrom().withNewSecretKeyRef().withName(name(spec.build.id)).withKey(TOKEN_KEY).endSecretKeyRef().endValueFrom()
            .build()
        return JobBuilder()
            .withNewMetadata().withName(name(spec.build.id)).withNamespace(spec.namespace).withLabels<String, String>(labels).endMetadata()
            .withNewSpec()
            .withBackoffLimit(0)
            .withActiveDeadlineSeconds(TIMEOUT_SECONDS)
            .withTtlSecondsAfterFinished(RETENTION_SECONDS)
            .withNewTemplate()
            .withNewMetadata().withLabels<String, String>(labels).endMetadata()
            .withNewSpec()
            .withRestartPolicy("Never")
            .withAutomountServiceAccountToken(false)
            .withNewSecurityContext().withRunAsUser(BUILDER_UID).withRunAsGroup(BUILDER_UID).withFsGroup(BUILDER_UID).endSecurityContext()
            .addNewContainer()
            .withName("build")
            .withImage(spec.buildImage)
            .withEnv(env.map { (name, value) -> EnvVar(name, value, null) } + token)
            .withNewResources()
            .withRequests<String, Quantity>(mapOf("cpu" to Quantity("500m"), "memory" to Quantity("1Gi")))
            .withLimits<String, Quantity>(mapOf("cpu" to Quantity("2"), "memory" to Quantity("4Gi")))
            .endResources()
            .withNewSecurityContext()
            .withNewSeccompProfile().withType("Unconfined").endSeccompProfile()
            .withNewAppArmorProfile().withType("Unconfined").endAppArmorProfile()
            .endSecurityContext()
            .addNewVolumeMount().withName("docker-config").withMountPath(DOCKER_CONFIG).withReadOnly(true).endVolumeMount()
            .endContainer()
            .addNewVolume().withName("docker-config")
            .withNewSecret().withSecretName(REGISTRY_SECRET).withOptional(true)
            .addNewItem().withKey(".dockerconfigjson").withPath("config.json").endItem()
            .endSecret()
            .endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }

    fun tokenSecret(spec: BuildJobSpec, owner: Job): Secret = SecretBuilder()
        .withNewMetadata()
        .withName(name(spec.build.id))
        .withNamespace(spec.namespace)
        .withOwnerReferences(OwnerReferenceBuilder().withApiVersion("batch/v1").withKind("Job").withName(owner.metadata.name).withUid(owner.metadata.uid).build())
        .endMetadata()
        .withStringData<String, String>(mapOf(TOKEN_KEY to spec.installationToken))
        .build()
}
