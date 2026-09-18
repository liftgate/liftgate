package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.deploy.Deployments
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun `a complete rollout is running`() =
        assertEquals(Rollout(testDeployment.id, DeploymentStatus.RUNNING, 2), Rollout.of(rollout()))

    @Test
    fun `old pods, missing pods and unavailable pods keep it releasing`() {
        listOf(rollout(replicas = 3), rollout(updated = 1), rollout(available = 1, ready = 1)).forEach {
            assertEquals(DeploymentStatus.RELEASING, Rollout.of(it)?.status)
        }
        assertEquals(1, Rollout.of(rollout(available = 1, ready = 1))?.replicasReady)
    }

    @Test
    fun `a status from before the latest apply is ignored`() = assertNull(Rollout.of(rollout(generation = 3, observed = 2)))

    @Test
    fun `deployments without a liftgate deployment label are ignored`() =
        assertNull(Rollout.of(DeploymentBuilder(rollout()).editMetadata().removeFromLabels(DEPLOYMENT_LABEL).endMetadata().build()))

    @Test
    fun `an exceeded progress deadline fails the deployment with the cluster message`() {
        val stalled = DeploymentBuilder(rollout(available = 0, ready = 0)).editStatus()
            .addNewCondition().withType("Progressing").withStatus("False").withReason("ProgressDeadlineExceeded").withMessage("api has timed out progressing").endCondition()
            .endStatus().build()
        assertEquals(Rollout(testDeployment.id, DeploymentStatus.FAILED, 0, "api has timed out progressing"), Rollout.of(stalled))
    }

    @Test
    fun `the informer records rollouts of managed deployments`() {
        val deployments = mockk<Deployments>(relaxUnitFun = true)
        val app = mockk<App> {
            every { this@mockk.scope } returns this@DeploymentWatcherTest.scope
            every { this@mockk.deployments } returns deployments
        }
        client.resource(rollout()).create()
        DeploymentWatcher(app, client).start().use {
            coVerify(timeout = 10_000) { deployments.transition(testDeployment.id, DeploymentStatus.RUNNING, 2, null) }
        }
    }
}
