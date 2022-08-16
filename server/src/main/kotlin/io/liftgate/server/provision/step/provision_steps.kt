package io.liftgate.server.provision.step

import io.liftgate.server.provision.step.impl.CopyStep
import io.liftgate.server.provision.step.impl.ExecutionStep
import io.liftgate.server.provision.step.impl.ReplacementStep

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
val orderedProvisionSteps = listOf(
    CopyStep, ReplacementStep, ExecutionStep
)
