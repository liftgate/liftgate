package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.deploy.DeploymentHealth
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.Deployments
import dev.liftgate.http.conflict
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.sql.SQLException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * @author Dean
 * @date 9/17/2026
 */
@EnableKubernetesMockClient(crud = true)
class DeploymentWatcherTest {
    lateinit var client: KubernetesClient

    private val scope = CoroutineScope(Dispatchers.Default)
    private val release = testRelease()

    private fun rollout(generation: Long = 2, observed: Long = 2, replicas: Int = 2, updated: Int = 2, available: Int = 2, ready: Int = 2): Deployment =
        DeploymentBuilder(Resources.deployment(release, null))
            .editMetadata().withGeneration(generation).endMetadata()
            .withStatus(
                DeploymentStatusBuilder()
                    .withObservedGeneration(observed).withReplicas(replicas).withUpdatedReplicas(updated).withAvailableReplicas(available).withReadyReplicas(ready)
                    .build(),
            )
            .build()

    private fun stalled(): Deployment = DeploymentBuilder(rollout(available = 0, ready = 0)).editStatus()
        .addNewCondition().withType("Progressing").withStatus("False").withReason("ProgressDeadlineExceeded").withMessage("api has timed out progressing").endCondition()
        .endStatus().build()

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun `a complete rollout is running`() =
        assertEquals(Rollout(testDeployment.id, DeploymentStatus.RUNNING, 2, DeploymentHealth.HEALTHY), Rollout.of(rollout()))

    @Test
    fun `old pods, missing pods and unavailable pods keep it releasing`() {
        listOf(rollout(replicas = 3), rollout(updated = 1), rollout(available = 1, ready = 1)).forEach {
            assertEquals(DeploymentStatus.RELEASING, Rollout.of(it)?.status)
        }
        assertEquals(1, Rollout.of(rollout(available = 1, ready = 1))?.replicasReady)
    }

    @Test
    fun `health is healthy with every replica ready, degraded with some and down with none`() {
        assertEquals(
            listOf(DeploymentHealth.HEALTHY, DeploymentHealth.DEGRADED, DeploymentHealth.DOWN),
            listOf(2, 1, 0).map { Rollout.of(rollout(available = it, ready = it))?.health },
        )
    }

    @Test
    fun `a status from before the latest apply is ignored`() = assertNull(Rollout.of(rollout(generation = 3, observed = 2)))

    @Test
    fun `deployments without a liftgate deployment label are ignored`() =
        assertNull(Rollout.of(DeploymentBuilder(rollout()).editMetadata().removeFromLabels(DEPLOYMENT_LABEL).endMetadata().build()))

    @Test
    fun `an exceeded progress deadline fails the deployment with the cluster message`() =
        assertEquals(Rollout(testDeployment.id, DeploymentStatus.FAILED, 0, DeploymentHealth.DOWN, "api has timed out progressing"), Rollout.of(stalled()))

    private fun app(deployments: Deployments) = mockk<App> {
        every { this@mockk.scope } returns this@DeploymentWatcherTest.scope
        every { this@mockk.deployments } returns deployments
    }

    @Test
    fun `the informer records rollouts of managed deployments`() {
        val deployments = mockk<Deployments>(relaxUnitFun = true)
        DeploymentWatcher(app(deployments), client).start().use {
            client.resource(rollout()).create()
            coVerify(timeout = 10_000) { deployments.transition(testDeployment.id, DeploymentStatus.RUNNING, 2, null) }
            coVerify(timeout = 10_000) { deployments.observe(testDeployment.id, 2, DeploymentHealth.HEALTHY) }
        }
    }

    @Test
    fun `a running deployment whose pods stop being ready is recorded down without a lifecycle transition`() {
        val deployments = mockk<Deployments>(relaxUnitFun = true) {
            coEvery { transition(testDeployment.id, DeploymentStatus.RELEASING, 0, null) } answers { conflict("deployment cannot move from running to releasing") }
        }
        DeploymentWatcher(app(deployments), client).start().use {
            client.resource(rollout(available = 0, ready = 0)).create()
            coVerify(timeout = 10_000) { deployments.observe(testDeployment.id, 0, DeploymentHealth.DOWN) }
        }
    }

    @Test
    fun `a failed rollout reverts to the deployment still running once the api server answers and says so in its error`() {
        val previous = testDeployment.copy(id = UUID.randomUUID(), status = DeploymentStatus.RUNNING)
        val deployments = mockk<Deployments>(relaxUnitFun = true) { coEvery { fallback(testDeployment.id) } returns previous }
        val reconciler = mockk<Reconciler> { coEvery { reapply(testService.id) } throws KubernetesClientException("unavailable", 503, null) andThen Unit }
        client.resource(stalled()).create()
        DeploymentWatcher(app(deployments), client, reconciler).start().use {
            coVerify(timeout = 15_000) { deployments.transition(testDeployment.id, DeploymentStatus.FAILED, 0, "api has timed out progressing; reverted to ${previous.id}") }
            coVerify(exactly = 2) { reconciler.reapply(testService.id) }
        }
    }

    @Test
    fun `a rollout that could not be written while postgres was unreachable is recorded once it is back`() {
        val deployments = mockk<Deployments>(relaxUnitFun = true) {
            coEvery { transition(testDeployment.id, DeploymentStatus.RUNNING, 2, null) } throws SQLException("connection refused") andThen Unit
        }
        DeploymentWatcher(app(deployments), client).start().use {
            client.resource(rollout()).create()
            coVerify(timeout = 15_000, exactly = 2) { deployments.transition(testDeployment.id, DeploymentStatus.RUNNING, 2, null) }
        }
    }

    @Test
    fun `a thousand idle deployments make no transition calls in three minutes`() {
        val deployments = mockk<Deployments>(relaxUnitFun = true)
        repeat(1000) {
            client.resource(DeploymentBuilder(rollout()).editMetadata().withName("api-$it").addToLabels(DEPLOYMENT_LABEL, UUID.randomUUID().toString()).endMetadata().build()).create()
        }
        DeploymentWatcher(app(deployments), client).start().use {
            coVerify(timeout = 60_000, exactly = 1000) { deployments.transition(any(), DeploymentStatus.RUNNING, 2, null) }
            clearMocks(deployments, answers = false)
            Thread.sleep(3.minutes.inWholeMilliseconds)
            coVerify(exactly = 0) { deployments.transition(any(), any(), any(), any()) }
        }
    }
}
