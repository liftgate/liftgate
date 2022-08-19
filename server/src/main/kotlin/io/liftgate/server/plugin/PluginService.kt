package io.liftgate.server.plugin

import io.liftgate.server.LiftgateEngine
import io.liftgate.server.startup.StartupStep
import org.pf4j.JarPluginManager
import java.io.File

/**
 * @author GrowlyX
 * @since 8/17/2022
 */
object PluginService : StartupStep, JarPluginManager(
    File("plugins").toPath()
)
{
    override fun perform(context: LiftgateEngine)
    {
        this.loadPlugins()
        this.startPlugins()
    }
}
