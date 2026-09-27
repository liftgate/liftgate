package dev.liftgate.build

import dev.liftgate.http.json
import dev.liftgate.k8s.testBuild
import dev.liftgate.k8s.testOrg
import dev.liftgate.k8s.testProject
import dev.liftgate.k8s.testService
import dev.liftgate.service.BuildStrategy
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.TolerationBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class BuildJobsTest {
    private val service = testService.copy(rootDir = "/apps/api", buildStrategy = BuildStrategy.DOCKERFILE, dockerfilePath = "docker/Dockerfile")
    private val image = BuildJobs.imageRef("registry.test", testOrg, testProject, service, testBuild.commitSha)
    private val cache = BuildJobs.imageRef("registry.test", testOrg, testProject, service, "cache")
    private val spec = BuildJobSpec(testBuild, service, testProject, "ghs_token", image, cache, "ghcr.io/liftgate/build-image:latest", "liftgate-build", emptyMap(), false)
    private val job = BuildJobs.job(spec)
    private val pod = job.spec.template.spec
    private val clone = pod.initContainers.single()
    private val container = pod.containers.single()
    private val owner = JobBuilder(job).editMetadata().withUid("job-uid").endMetadata().build()

    @Test
    fun `image refs are registry, org, project-service and a tag without slashes`() {
        assertEquals("registry.test/acme/shop-api:abc123", image)
        assertEquals("registry.test/acme/shop-api:cache", cache)
        assertEquals("registry.test/acme/shop-api:feature-login", BuildJobs.imageRef("registry.test", testOrg, testProject, service, "feature/login"))
    }

    @Test
    fun `job is named after the build, runs once unless its node is drained and stops after thirty minutes`() {
        assertEquals("build-${testBuild.id}", job.metadata.name)
        assertEquals("liftgate-build", job.metadata.namespace)
        assertEquals(testBuild.id.toString(), job.metadata.labels[BUILD_LABEL])
        assertEquals(job.metadata.labels, job.spec.template.metadata.labels)
        assertEquals(0, job.spec.backoffLimit)
        val rule = job.spec.podFailurePolicy.rules.single()
        assertEquals(listOf("Ignore", "DisruptionTarget", "True"), listOf(rule.action, rule.onPodConditions.single().type, rule.onPodConditions.single().status))
        assertEquals(1800L, job.spec.activeDeadlineSeconds)
        assertEquals("Never", pod.restartPolicy)
        assertEquals(false, pod.automountServiceAccountToken)
    }

    @Test
    fun `only the clone container holds the git token and both share the workspace`() {
        assertEquals("/usr/local/bin/clone.sh", clone.command.single())
        assertEquals(
            mapOf("LIFTGATE_REPO_URL" to "https://github.com/acme/shop.git", "LIFTGATE_COMMIT" to "abc123", "LIFTGATE_GIT_TOKEN" to null),
            clone.env.associate { it.name to it.value },
        )
        assertTrue(container.env.none { it.valueFrom != null })
        assertTrue(container.envFrom.isNullOrEmpty())
        assertTrue(pod.volumes.single { it.name == "workspace" }.emptyDir != null)
        listOf(clone, container).forEach { assertEquals("/workspace", it.volumeMounts.single { mount -> mount.name == "workspace" }.mountPath) }
    }

    @Test
    fun `the build container receives the build settings`() {
        assertEquals("ghcr.io/liftgate/build-image:latest", container.image)
        assertEquals(
            mapOf(
                "LIFTGATE_ROOT_DIR" to "/apps/api",
                "LIFTGATE_BUILD_STRATEGY" to "dockerfile",
                "LIFTGATE_DOCKERFILE_PATH" to "docker/Dockerfile",
                "IMAGE" to image,
                "CACHE" to cache,
                "DOCKER_CONFIG" to "/home/user/.docker",
                "LIFTGATE_REGISTRY_INSECURE" to "false",
            ),
            container.env.associate { it.name to it.value },
        )
    }

    @Test
    fun `each container receives every variable its script reads`() {
        val provided = Regex("""^ENV (\w+)=""", RegexOption.MULTILINE).findAll(File("../build-image/Dockerfile").readText()).map { it.groupValues[1] }.toSet()
        mapOf("build.sh" to container, "clone.sh" to clone).forEach { (script, target: Container) ->
            Regex("""\$\{?([A-Z_]+)""").findAll(File("../build-image/$script").readText()).map { it.groupValues[1] }.filter { it !in provided }.forEach {
                assertTrue(target.env.any { env -> env.name == it }, "$script reads $it which the ${target.name} container does not get")
            }
        }
    }

    @Test
    fun `node selector, tolerations and insecure registry reach the pod`() {
        val toleration = TolerationBuilder().withKey("liftgate.dev/pool").withValue("build").withEffect("NoSchedule").build()
        val pinned = BuildJobs.job(spec.copy(nodeSelector = mapOf("liftgate.dev/pool" to "build"), tolerations = listOf(toleration), registryInsecure = true)).spec.template.spec
        assertTrue(pod.nodeSelector.isNullOrEmpty())
        assertTrue(pod.tolerations.isNullOrEmpty())
        assertEquals(mapOf("liftgate.dev/pool" to "build"), pinned.nodeSelector)
        assertEquals(listOf(toleration), pinned.tolerations)
        assertEquals("true", pinned.containers.single().env.single { it.name == "LIFTGATE_REGISTRY_INSECURE" }.value)
    }

    @Test
    fun `the git token is read from a secret the job owns and never appears in the job`() {
        val secret = BuildJobs.tokenSecret(spec, owner)
        val reference = clone.env.single { it.name == "LIFTGATE_GIT_TOKEN" }.valueFrom.secretKeyRef
        assertEquals(secret.metadata.name to "token", reference.name to reference.key)
        assertEquals(mapOf("token" to "ghs_token"), secret.stringData)
        assertEquals(listOf("Job", job.metadata.name, "job-uid"), secret.metadata.ownerReferences.single().let { listOf(it.kind, it.name, it.uid) })
        assertFalse("ghs_token" in Serialization.asJson(job))
    }

    @Test
    fun `rootless buildkit runs as user 1000 without seccomp or apparmor confinement and at most 20Gi of disk`() {
        assertEquals(1000L, pod.securityContext.runAsUser)
        assertEquals("Unconfined", container.securityContext.seccompProfile.type)
        assertEquals("Unconfined", container.securityContext.appArmorProfile.type)
        assertEquals(Quantity("20Gi"), container.resources.requests["ephemeral-storage"])
        assertEquals(Quantity("20Gi"), container.resources.limits["ephemeral-storage"])
    }

    @Test
    fun `shared registry credentials mount as the docker config when the secret exists`() {
        val volume = pod.volumes.single { it.name == "docker-config" }
        val mount = container.volumeMounts.single { it.name == "docker-config" }
        assertEquals(REGISTRY_SECRET, volume.secret.secretName)
        assertEquals(true, volume.secret.optional)
        assertEquals(".dockerconfigjson" to "config.json", volume.secret.items.single().let { it.key to it.path })
        assertEquals("/home/user/.docker", mount.mountPath)
    }

    @Test
    fun `token auth mounts the build's own registry login and never the shared credentials`() {
        val tokenSpec = spec.copy(registryTokenAuth = true)
        val volumes = BuildJobs.job(tokenSpec).spec.template.spec.volumes
        assertTrue(volumes.none { it.secret?.secretName == REGISTRY_SECRET })
        val volume = volumes.single { it.name == "docker-config" }
        assertEquals(BuildJobs.name(testBuild.id) to false, volume.secret.secretName to volume.secret.optional)
        val secret = BuildJobs.tokenSecret(tokenSpec, owner, "registry-password")
        val auth = json.parseToJsonElement(secret.stringData.getValue(".dockerconfigjson")).jsonObject.getValue("auths").jsonObject.getValue("registry.test").jsonObject.getValue("auth")
        assertEquals("build-${testBuild.id}:registry-password", Base64.getDecoder().decode(auth.jsonPrimitive.content).decodeToString())
        assertNull(BuildJobs.tokenSecret(tokenSpec, owner).stringData[".dockerconfigjson"])
    }
}
