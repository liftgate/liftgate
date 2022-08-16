package io.liftgate.server.command

import io.liftgate.server.LiftgateEngine
import io.liftgate.server.logger
import io.liftgate.server.provision.ProvisionHandler
import io.liftgate.server.server.ServerHandler
import io.liftgate.server.server.ServerTemplateHandler
import io.liftgate.server.startup.StartupStep
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.cli.ConsoleActor
import revxrsal.commands.cli.ConsoleCommandHandler
import revxrsal.commands.exception.CommandErrorException
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
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

    @Subcommand("provision")
    suspend fun onProvision(
        actor: ConsoleActor,
        templateName: String, // TODO: add proper context resolver
        @Optional uid: String?,
        @Optional port: Int?,
        continuation: Continuation<Unit>
    )
    {
        val template = ServerTemplateHandler
            .findTemplateById(templateName)
            ?: throw CommandErrorException(
                "No template by that name exists."
            )

        actor.reply("Provisioning...")

        ProvisionHandler.provision(
            template, uid, port, continuation
        )
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
