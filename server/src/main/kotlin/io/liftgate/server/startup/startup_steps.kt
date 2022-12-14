package io.liftgate.server.startup

import io.liftgate.server.autoscale.AutoScaleHandler
import io.liftgate.server.command.CommandHandler
import io.liftgate.server.provision.ProvisionHandler
import io.liftgate.server.resource.ResourceHandler
import io.liftgate.server.server.ServerHandler
import io.liftgate.server.server.ServerTemplateHandler
import io.liftgate.server.token.TokenGeneratorStep

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
val steps = mutableListOf(
    TokenGeneratorStep, CommandHandler, ResourceHandler,
    ServerTemplateHandler, ServerHandler, ProvisionHandler,
    AutoScaleHandler
)
