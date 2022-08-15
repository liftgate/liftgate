package io.liftgate.server.startup

import io.liftgate.server.LiftgateEngine

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
interface StartupStep
{
    fun perform(context: LiftgateEngine)
}
