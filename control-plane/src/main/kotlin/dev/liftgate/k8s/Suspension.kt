package dev.liftgate.k8s

import dev.liftgate.App
import dev.liftgate.events.Subject
import dev.liftgate.events.uuid
import io.fabric8.kubernetes.client.KubernetesClient
import java.util.UUID

/**
 * @author Dean
 * @date 9/27/2026
 */
class Suspension(private val app: App, kube: KubernetesClient) {
    private val reconciler = Reconciler(app, kube)

    suspend fun reapply(orgId: UUID) = app.services.idsForOrg(orgId).forEach { reconciler.reapply(it) }

    fun start() = mapOf(Subject.ORG_SUSPENDED to "reconciler-org-suspended", Subject.ORG_UNSUSPENDED to "reconciler-org-unsuspended")
        .map { (subject, durable) -> app.nats.consume(subject, durable, app.scope) { reapply(it.uuid("orgId")) } }
}
