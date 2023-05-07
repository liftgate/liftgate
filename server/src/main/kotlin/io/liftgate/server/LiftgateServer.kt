package io.liftgate.server

import io.liftgate.server.startup.steps
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.nio.file.Files
import java.util.logging.Level
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object LiftgateServer
{
    @JvmStatic
    @OptIn(ExperimentalSerializationApi::class)
    fun main(args: Array<String>)
    {
        val configuration =
            File("liftgate.json")

        config = if (!configuration.exists())
        {
            LiftgateServerConfig()
        } else
        {
            Json.decodeFromStream(
                configuration.inputStream()
            )
        }

        // update config file with new contents
        this.saveConfig(configuration)

        val engine = LiftgateEngine()

        for ((index, step) in steps.withIndex())
        {
            val measured = measureTimeMillis {
                kotlin
                    .runCatching {
                        step.perform(engine)
                    }
                    .onFailure {
                        logger.info("[Startup] Failed on step: ${step.javaClass.name}")
                        it.printStackTrace()
                        exitProcess(0)
                    }
            }

            logger.info("[Startup] Completed setup step #${index + 1} in $measured ms (${step.javaClass.name})")
        }

        logger.info("[Startup] Starting RPC server...")
        engine.start()

        logger.info("[Startup] Completed startup, now listening for commands.")
    }

    private fun saveConfig(configuration: File)
    {
        val content = Json
            .encodeToString(config)
            .encodeToByteArray()

        Files.write(
            configuration.toPath(), content
        )
    }
}
