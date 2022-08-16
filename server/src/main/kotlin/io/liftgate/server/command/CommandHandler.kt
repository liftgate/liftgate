package io.liftgate.server.command

import io.liftgate.server.LiftgateEngine
import io.liftgate.server.startup.StartupStep
import io.liftgate.server.logger
import io.liftgate.server.server.ServerHandler
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.cli.ConsoleActor
import revxrsal.commands.cli.ConsoleCommandHandler
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
@Command("liftgate")
object CommandHandler : StartupStep
{
    override fun perform(context: LiftgateEngine)
    {
        val handler = ConsoleCommandHandler.create()
        handler.register(this)

        thread {
            while (true)
            {
                handler.pollInput()
            }
        }
    }

    @Subcommand("servers")
    fun onServers(actor: ConsoleActor)
    {
        actor.reply("Servers:")

        ServerHandler.findAllServers()
            .forEach {
                actor.reply(" - ${it.serverId}: ${it.datacenter}")
            }
    }

    @Subcommand("stop")
    fun stop(actor: ConsoleActor)
    {
        logger.info("Stopping...")
        exitProcess(1)
    }
}
