package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.Deployments
import io.fabric8.kubernetes.api.model.ContainerStatus
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * @author Dean
 * @date 9/27/2026
 */
@EnableKubernetesMockClient(crud = true)
class PodWatcherTest {
    lateinit var client: KubernetesClient

    private val scope = CoroutineScope(Dispatchers.Default)
    private val release = testRelease()
    private val unreleased = setOf(DeploymentStatus.PENDING, DeploymentStatus.RELEASING)

    private fun pod(status: ContainerStatus, deployment: UUID = testDeployment.id): Pod =
        PodBuilder().withNewMetadata().withName("api-$deployment").withNamespace(release.namespace).addToLabels(MANAGED_LABEL, "true").addToLabels(DEPLOYMENT_LABEL, deployment.toString()).endMetadata()
            .withNewStatus().withContainerStatuses(status).endStatus()
            .build()

    private fun container(restarts: Int = 1) = ContainerStatusBuilder().withName("app").withRestartCount(restarts)

    private fun crashing(message: String? = null) = container()
        .withNewState().withNewWaiting().withReason("CrashLoopBackOff").withMessage("back-off 10s restarting failed container=app").endWaiting().endState()
        .withNewLastState().withNewTerminated().withExitCode(1).withReason("Error").withMessage(message).endTerminated().endLastState()
        .build()

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun `a crash loop names the exit code and the last termination message`() {
        assertEquals("CrashLoopBackOff, exit code 1", PodWatcher.failure(pod(crashing())))
        assertEquals("CrashLoopBackOff, exit code 1: DATABASE_URL is not set", PodWatcher.failure(pod(crashing("DATABASE_URL is not set\n"))))
        assertEquals("CrashLoopBackOff, exit code 1: boom\nat main", PodWatcher.failure(pod(crashing("boom\u0000\r\nat main"))))
    }

    @Test
    fun `an image or config that cannot start the container fails with the kubelet message`() {
        listOf("ImagePullBackOff" to "Back-off pulling image \"registry.test/acme/shop-api:abc123\"", "ErrImagePull" to "not found", "CreateContainerConfigError" to "secret \"api-env\" not found")
            .forEach { (reason, message) ->
                val status = container(restarts = 0).withNewState().withNewWaiting().withReason(reason).withMessage(message).endWaiting().endState().build()
                assertEquals("$reason: $message", PodWatcher.failure(pod(status)))
            }
    }

    @Test
    fun `a third restart fails even while the container runs again`() {
        val restarted = { restarts: Int ->
            container(restarts).withNewState().withNewRunning().endRunning().endState()
                .withNewLastState().withNewTerminated().withExitCode(137).withReason("OOMKilled").endTerminated().endLastState().build()
        }
        assertNull(PodWatcher.failure(pod(restarted(2))))
        assertEquals("restarted 3 times, exit code 137", PodWatcher.failure(pod(restarted(3))))
    }

    @Test
    fun `starting and running containers are not failures`() {
        assertNull(PodWatcher.failure(pod(container(restarts = 0).withNewState().withNewWaiting().withReason("ContainerCreating").endWaiting().endState().build())))
        assertNull(PodWatcher.failure(pod(container(restarts = 0).withNewState().withNewRunning().endRunning().endState().build())))
        assertNull(PodWatcher.failure(PodBuilder(pod(crashing())).withStatus(null).build()))
    }

    private fun app(deployments: Deployments) = mockk<App> {
        every { this@mockk.scope } returns this@PodWatcherTest.scope
        every { this@mockk.deployments } returns deployments
    }

    @Test
    fun `a crash loop fails its deployment only while the deployment is unreleased`() {
        val deployments = mockk<Deployments>(relaxUnitFun = true)
        client.resource(pod(crashing())).create()
        PodWatcher(app(deployments), client).start().use {
            coVerify(timeout = 10_000) { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, 0, "CrashLoopBackOff, exit code 1", unreleased) }
        }
    }

    @Test
    fun `a failure that could not be written while postgres was unreachable is written once it is back`() {
        val deployments = mockk<Deployments> {
            coEvery { transition(testDeployment.id, DeploymentStatus.FAILED, 0, "CrashLoopBackOff, exit code 1", unreleased) } throws SQLTransientConnectionException("connection refused") andThen Unit
        }
        client.resource(pod(crashing())).create()
        PodWatcher(app(deployments), client).start().use {
            coVerify(timeout = 15_000, exactly = 2) { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, 0, "CrashLoopBackOff, exit code 1", unreleased) }
        }
    }

    @Test
    fun `a failure postgres rejects is dropped instead of holding up other deployments`() {
        val other = UUID.randomUUID()
        val deployments = mockk<Deployments>(relaxUnitFun = true) {
            coEvery { transition(testDeployment.id, DeploymentStatus.FAILED, 0, any(), unreleased) } throws SQLException("invalid byte sequence for encoding \"UTF8\": 0x00", "22021")
        }
        client.resource(pod(crashing())).create()
        PodWatcher(app(deployments), client).start().use {
            coVerify(timeout = 10_000) { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, 0, "CrashLoopBackOff, exit code 1", unreleased) }
            client.resource(pod(crashing(), other)).create()
            coVerify(timeout = 10_000) { deployments.transition(other, DeploymentStatus.FAILED, 0, "CrashLoopBackOff, exit code 1", unreleased) }
        }
    }
}
