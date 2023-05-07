package io.liftgate.server.command

import io.liftgate.server.LiftgateEngine
import io.liftgate.server.autoscale.AutoScaleHandler
import io.liftgate.server.autoscale.provision.impl.IncrementalPropertyChoiceScheme
import io.liftgate.server.logger
import io.liftgate.server.provision.ProvisionHandler
import io.liftgate.server.provision.ProvisionedServers
import io.liftgate.server.server.ServerHandler
import io.liftgate.server.server.ServerTemplateHandler
import io.liftgate.server.startup.StartupStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.cli.ConsoleActor
import revxrsal.commands.cli.ConsoleCommandHandler
import revxrsal.commands.exception.CommandErrorException
import revxrsal.commands.ktx.supportSuspendFunctions
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
    private val scope = CoroutineScope(Dispatchers.Default)

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
    fun onProvision(
        actor: ConsoleActor,
        templateName: String, // TODO: add proper context resolver
        @Optional uid: String?,
        @Optional port: Int?
    )
    {
        val template = ServerTemplateHandler
            .findTemplateById(templateName)
            ?: throw CommandErrorException(
                "No template by that name exists."
            )

        actor.reply("Provisioning...")

        scope.launch {
            ProvisionHandler.provision(
                template, uid, port,
                defaultMeta = mutableMapOf(
                    "propertyScheme" to IncrementalPropertyChoiceScheme::class.java.name
                )
            )
        }
    }

    @Subcommand("autoscale")
    fun onAutoScale(
        actor: ConsoleActor,
        templateName: String
    )
    {
        val template = AutoScaleHandler
            .findAutoScaleTemplateById(templateName)
            ?: throw CommandErrorException(
                "No template by that name exists."
            )

        if (template.startedAutoScale)
        {
            throw CommandErrorException("Auto scale has already been started for this template.")
        }

        AutoScaleHandler
            .startAutoScaleService(template)

        actor.reply("Started auto scale!")
    }

    @Subcommand("servers")
    fun onServers(actor: ConsoleActor)
    {
        actor.reply("Servers:")

        ServerHandler.findAllServers()
            .forEach {
                actor.reply(" - ${it.serverId}: ${it.datacenter}")
            }

        actor.reply("Auto-scale/liftgate provisioned:")

        ProvisionedServers.servers
            .forEach {
                actor.reply(" - ${it.id}: ${it.port}")
            }
    }

    @Subcommand("stop")
    fun stop(actor: ConsoleActor)
    {
        logger.info("Stopping...")
        exitProcess(1)
    }
}
