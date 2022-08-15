package io.liftgate.server

import io.liftgate.server.models.Resource
import io.liftgate.server.models.server.ServerTemplate
import java.util.logging.Logger

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
lateinit var config: LiftgateConfig

val logger = Logger.getGlobal()!!

val resources = mutableListOf<Resource>()
val templates = mutableListOf<ServerTemplate>()
