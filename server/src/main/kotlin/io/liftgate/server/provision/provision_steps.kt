package io.liftgate.server.provision

import io.liftgate.server.provision.impl.CopyStep
import io.liftgate.server.provision.impl.ExecutionStep
import io.liftgate.server.provision.impl.ReplacementStep

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
val orderedProvisionSteps = listOf(
    CopyStep, ReplacementStep, ExecutionStep
)
