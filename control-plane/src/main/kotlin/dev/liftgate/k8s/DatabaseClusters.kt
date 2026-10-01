package dev.liftgate.k8s

import dev.liftgate.database.Backup
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * @author Dean
 * @date 9/30/2026
 */
class DatabaseClusters(private val kube: KubernetesClient) {
    suspend fun ready(namespace: String): Set<String> = read(emptySet()) {
        kube.genericKubernetesResources(clusterContext).inNamespace(namespace).list().items
            .filter { cluster -> cluster.get<List<Map<String, Any?>>>("status", "conditions").orEmpty().any { it["type"] == "Ready" && it["status"] == "True" } }
            .map { it.metadata.name }
            .toSet()
    }

    suspend fun uri(namespace: String, database: String): String? = read(null) {
        kube.secrets().inNamespace(namespace).withName("$database-app").get()?.data?.get("uri")?.let { String(Base64.getDecoder().decode(it)) }
    }

    suspend fun backups(namespace: String, database: String): List<Backup> = read(emptyList()) {
        kube.genericKubernetesResources(backupContext).inNamespace(namespace).list().items
            .filter { it.get<String>("spec", "cluster", "name") == database }
            .map { Backup(it.metadata.name, it.get("status", "phase"), it.get("status", "startedAt"), it.get("status", "stoppedAt")) }
            .sortedByDescending { it.startedAt }
    }

    private suspend fun <T> read(missing: T, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: KubernetesClientException) {
            if (e.code != 403 && e.code != 404) throw e
            missing
        }
    }
}
