package dev.liftgate.build

import dev.liftgate.db.sql
import dev.liftgate.deploy.Build
import dev.liftgate.k8s.MANAGED_LABEL
import dev.liftgate.project.Project
import dev.liftgate.service.EnvVar
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceScope
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.Toleration
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.util.UUID
import io.fabric8.kubernetes.api.model.EnvVar as KubeEnvVar

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
    val nodeSelector: Map<String, String>,
    val registryInsecure: Boolean,
    val registryTokenAuth: Boolean = false,
    val tolerations: List<Toleration> = emptyList(),
    val productionCacheRef: String? = null,
    val variables: List<EnvVar> = emptyList(),
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
    const val DOCKER_CONFIG_KEY = ".dockerconfigjson"
    private const val WORKSPACE = "/workspace"
    private const val TOKEN_KEY = "token"
    private const val VARIABLE_KEY = "env."
    private const val VARIABLE_ENV = "LIFTGATE_ENV_"
    private const val EPHEMERAL_STORAGE = "20Gi"

    fun name(buildId: UUID) = "build-$buildId"

    fun repository(scope: ServiceScope) = with(scope) { "${org.slug}/${project.slug}/${environment.slug}/${service.slug}" }

    fun previousRepository(scope: ServiceScope) = with(scope) { "${org.slug}/${project.slug}-${service.slug}" }

    fun imageRef(registry: String, scope: ServiceScope, sha: String) = "$registry/${repository(scope)}:${sha.replace('/', '-')}"

    fun imageRef(job: Job): String = job.spec.template.spec.containers.single().env.single { it.name == "IMAGE" }.value

    fun job(spec: BuildJobSpec): Job {
        val labels = mapOf(MANAGED_LABEL to "true", BUILD_LABEL to spec.build.id.toString())
        val env = mapOf(
            "LIFTGATE_ROOT_DIR" to spec.service.rootDir,
            "LIFTGATE_BUILD_STRATEGY" to spec.service.buildStrategy.sql,
            "LIFTGATE_DOCKERFILE_PATH" to spec.service.dockerfilePath,
            "IMAGE" to spec.imageRef,
            "CACHE" to spec.cacheRef,
            "DOCKER_CONFIG" to DOCKER_CONFIG,
            "LIFTGATE_REGISTRY_INSECURE" to spec.registryInsecure.toString(),
            "PRODUCTION_CACHE" to spec.productionCacheRef.orEmpty(),
            "LIFTGATE_BUILD_ENV_NAMES" to spec.variables.joinToString(" ") { it.name },
            "LIFTGATE_BUILD_ARG_NAMES" to spec.variables.filterNot { it.secret }.joinToString(" ") { it.name },
        )
        val storage = mapOf("ephemeral-storage" to Quantity(EPHEMERAL_STORAGE))
        return JobBuilder()
            .withNewMetadata().withName(name(spec.build.id)).withNamespace(spec.namespace).withLabels<String, String>(labels).endMetadata()
            .withNewSpec()
            .withBackoffLimit(0)
            .withNewPodFailurePolicy()
            .addNewRule().withAction("Ignore").addNewOnPodCondition().withType("DisruptionTarget").withStatus("True").endOnPodCondition().endRule()
            .endPodFailurePolicy()
            .withActiveDeadlineSeconds(TIMEOUT_SECONDS)
            .withTtlSecondsAfterFinished(RETENTION_SECONDS)
            .withNewTemplate()
            .withNewMetadata().withLabels<String, String>(labels).endMetadata()
            .withNewSpec()
            .withRestartPolicy("Never")
            .withNodeSelector<String, String>(spec.nodeSelector)
            .withTolerations(spec.tolerations)
            .withAutomountServiceAccountToken(false)
            .withNewSecurityContext().withRunAsUser(BUILDER_UID).withRunAsGroup(BUILDER_UID).withFsGroup(BUILDER_UID).endSecurityContext()
            .addNewInitContainer()
            .withName("clone")
            .withImage(spec.buildImage)
            .withCommand("/usr/local/bin/clone.sh")
            .withEnv(
                KubeEnvVar("LIFTGATE_REPO_URL", "https://github.com/${spec.project.repoFullName}.git", null),
                KubeEnvVar("LIFTGATE_COMMIT", spec.build.commitSha, null),
                secretEnv(spec, "LIFTGATE_GIT_TOKEN", TOKEN_KEY),
            )
            .addNewVolumeMount().withName("workspace").withMountPath(WORKSPACE).endVolumeMount()
            .endInitContainer()
            .addNewContainer()
            .withName("build")
            .withImage(spec.buildImage)
            .withEnv(env.map { (name, value) -> KubeEnvVar(name, value, null) } + spec.variables.map { secretEnv(spec, VARIABLE_ENV + it.name, VARIABLE_KEY + it.name) })
            .withNewResources()
            .withRequests<String, Quantity>(mapOf("cpu" to Quantity("500m"), "memory" to Quantity("1Gi")) + storage)
            .withLimits<String, Quantity>(mapOf("cpu" to Quantity("2"), "memory" to Quantity("4Gi")) + storage)
            .endResources()
            .withNewSecurityContext()
            .withNewSeccompProfile().withType("Unconfined").endSeccompProfile()
            .withNewAppArmorProfile().withType("Unconfined").endAppArmorProfile()
            .endSecurityContext()
            .addNewVolumeMount().withName("docker-config").withMountPath(DOCKER_CONFIG).withReadOnly(true).endVolumeMount()
            .addNewVolumeMount().withName("workspace").withMountPath(WORKSPACE).endVolumeMount()
            .endContainer()
            .addNewVolume().withName("docker-config")
            .withNewSecret().withSecretName(if (spec.registryTokenAuth) name(spec.build.id) else REGISTRY_SECRET).withOptional(!spec.registryTokenAuth)
            .addNewItem().withKey(DOCKER_CONFIG_KEY).withPath("config.json").endItem()
            .endSecret()
            .endVolume()
            .addNewVolume().withName("workspace").withNewEmptyDir().endEmptyDir().endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }

    fun tokenSecret(spec: BuildJobSpec, owner: Job, registryPassword: String? = null): Secret = SecretBuilder()
        .withNewMetadata()
        .withName(name(spec.build.id))
        .withNamespace(spec.namespace)
        .withOwnerReferences(OwnerReferenceBuilder().withApiVersion("batch/v1").withKind("Job").withName(owner.metadata.name).withUid(owner.metadata.uid).build())
        .endMetadata()
        .withStringData<String, String>(
            mapOf(TOKEN_KEY to spec.installationToken) + spec.variables.associate { VARIABLE_KEY + it.name to it.value.orEmpty() } +
                listOfNotNull(registryPassword?.let { DOCKER_CONFIG_KEY to dockerConfig(spec, it) }),
        )
        .build()

    private fun secretEnv(spec: BuildJobSpec, env: String, key: String) = EnvVarBuilder().withName(env)
        .withNewValueFrom().withNewSecretKeyRef().withName(name(spec.build.id)).withKey(key).endSecretKeyRef().endValueFrom()
        .build()

    private fun dockerConfig(spec: BuildJobSpec, password: String) = buildJsonObject {
        putJsonObject("auths") {
            putJsonObject(spec.imageRef.substringBefore('/')) {
                put("auth", Base64.getEncoder().encodeToString("${name(spec.build.id)}:$password".toByteArray()))
            }
        }
    }.toString()
}
