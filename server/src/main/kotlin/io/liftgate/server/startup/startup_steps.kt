package io.liftgate.server.startup

import io.liftgate.server.command.CommandHandlerStep
import io.liftgate.server.token.TokenGeneratorStep

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
val steps = mutableListOf(
    TokenGeneratorStep, CommandHandlerStep
)
