package dev.liftgate.k8s

import io.fabric8.kubernetes.client.KubernetesClientException

val KubernetesClientException.retryable get() = code !in 400..499 || code == 409 || code == 429
