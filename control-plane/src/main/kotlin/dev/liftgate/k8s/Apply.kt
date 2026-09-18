package dev.liftgate.k8s

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.dsl.Resource

fun <T : HasMetadata> Resource<T>.apply(): T = fieldManager("liftgate").forceConflicts().serverSideApply()
