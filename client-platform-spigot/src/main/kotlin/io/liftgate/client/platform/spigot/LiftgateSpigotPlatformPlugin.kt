package io.liftgate.client.platform.spigot

import com.github.shynixn.mccoroutine.bukkit.asyncDispatcher
import com.github.shynixn.mccoroutine.bukkit.launch
import io.liftgate.client.LiftgateClientConfig
import io.liftgate.client.LiftgateHeartbeatService
import io.liftgate.client.RegistrationInfo
import io.liftgate.client.coroutine.LiftgateAsyncClient
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * @author GrowlyX
 * @since 8/16/2022
 */
class LiftgateSpigotPlatformPlugin : JavaPlugin()
{
    private lateinit var client: LiftgateAsyncClient

    override fun onEnable()
    {
        this.saveDefaultConfig()

        val registrationInfo = RegistrationInfo(
            serverId = this.config.getString("server-id")!!,
            datacenter = this.config.getString("datacenter")!!,
            groups = this.config.getStringList("groups"),
            port = Bukkit.getPort()
        )

        val liftgateClientConfig = LiftgateClientConfig(
            hostname = this.config.getString("hostname")!!,
            authToken = this.config.getString("auth-token")!!,
            port = this.config.getInt("port"),
            maxInboundMessageSize = this.config.getInt("max-inbound-message-size"),
            registrationInfo = registrationInfo
        )

        val defaultMetadata = this.config
            .getConfigurationSection("default-metadata")!!
            .let {
                it.getKeys(false)
                    .associateWith { key ->
                        // this should never be null... I hope
                        it.getString(key)!!
                    }
            }

        val metadataSupplier = context@{
            val metadata = mutableMapOf<String, String>()
            metadata.putAll(defaultMetadata)

            metadata["players"] = server.onlinePlayers.size.toString()
            metadata["max-players"] = server.maxPlayers.toString()

            return@context metadata
        }

        launch {
            withContext(asyncDispatcher) {
                client = LiftgateAsyncClient(
                    liftgateClientConfig, logger, metadataSupplier
                )
                client.initialize()

                val heartbeat = LiftgateHeartbeatService(client)
                heartbeat.configure().join()
            }
        }
    }

    override fun onDisable()
    {
        this.client.close()
    }
}
