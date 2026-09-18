package dev.liftgate.build

import dev.liftgate.k8s.testBuild
import dev.liftgate.k8s.testOrg
import dev.liftgate.k8s.testProject
import dev.liftgate.k8s.testService
import dev.liftgate.service.BuildStrategy
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    private val container = pod.containers.single()

    @Test
    fun `image refs are registry, org, project-service and a tag without slashes`() {
        assertEquals("registry.test/acme/shop-api:abc123", image)
        assertEquals("registry.test/acme/shop-api:cache", cache)
        assertEquals("registry.test/acme/shop-api:feature-login", BuildJobs.imageRef("registry.test", testOrg, testProject, service, "feature/login"))
    }

    @Test
    fun `job is named after the build, runs once and stops after thirty minutes`() {
        assertEquals("build-${testBuild.id}", job.metadata.name)
        assertEquals("liftgate-build", job.metadata.namespace)
        assertEquals(testBuild.id.toString(), job.metadata.labels[BUILD_LABEL])
        assertEquals(job.metadata.labels, job.spec.template.metadata.labels)
        assertEquals(0, job.spec.backoffLimit)
        assertEquals(1800L, job.spec.activeDeadlineSeconds)
        assertEquals("Never", pod.restartPolicy)
        assertEquals(false, pod.automountServiceAccountToken)
    }

    @Test
    fun `container receives everything build sh reads`() {
        assertEquals("ghcr.io/liftgate/build-image:latest", container.image)
        assertEquals(
            mapOf(
                "LIFTGATE_REPO_URL" to "https://github.com/acme/shop.git",
                "LIFTGATE_GIT_TOKEN" to null,
                "LIFTGATE_COMMIT" to "abc123",
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
        val script = File("../build-image/build.sh").readText()
        Regex("""\$\{?([A-Z_]+)""").findAll(script).map { it.groupValues[1] }.filter { it != "HOME" }.forEach {
            assertTrue(container.env.any { env -> env.name == it }, "build.sh reads $it which the job does not set")
        }
    }

    @Test
    fun `node selector and insecure registry reach the pod`() {
        val pinned = BuildJobs.job(spec.copy(nodeSelector = mapOf("kubernetes.io/hostname" to "n1"), registryInsecure = true)).spec.template.spec
        assertTrue(pod.nodeSelector.isNullOrEmpty())
        assertEquals(mapOf("kubernetes.io/hostname" to "n1"), pinned.nodeSelector)
        assertEquals("true", pinned.containers.single().env.single { it.name == "LIFTGATE_REGISTRY_INSECURE" }.value)
    }

    @Test
    fun `the git token is read from a secret the job owns and never appears in the job`() {
        val secret = BuildJobs.tokenSecret(spec, JobBuilder(job).editMetadata().withUid("job-uid").endMetadata().build())
        val reference = container.env.single { it.name == "LIFTGATE_GIT_TOKEN" }.valueFrom.secretKeyRef
        assertEquals(secret.metadata.name to "token", reference.name to reference.key)
        assertEquals("ghs_token", secret.stringData["token"])
        assertEquals(listOf("Job", job.metadata.name, "job-uid"), secret.metadata.ownerReferences.single().let { listOf(it.kind, it.name, it.uid) })
        assertFalse("ghs_token" in Serialization.asJson(job))
    }

    @Test
    fun `rootless buildkit runs as user 1000 without seccomp or apparmor confinement`() {
        assertEquals(1000L, pod.securityContext.runAsUser)
        assertEquals("Unconfined", container.securityContext.seccompProfile.type)
        assertEquals("Unconfined", container.securityContext.appArmorProfile.type)
    }

    @Test
    fun `registry credentials mount as the docker config when the secret exists`() {
        val volume = pod.volumes.single()
        val mount = container.volumeMounts.single()
        assertEquals(REGISTRY_SECRET, volume.secret.secretName)
        assertEquals(true, volume.secret.optional)
        assertEquals(".dockerconfigjson" to "config.json", volume.secret.items.single().let { it.key to it.path })
        assertEquals(volume.name to "/home/user/.docker", mount.name to mount.mountPath)
    }
}
