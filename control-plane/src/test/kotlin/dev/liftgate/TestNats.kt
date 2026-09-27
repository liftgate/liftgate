package dev.liftgate

import dev.liftgate.events.Nats
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * @author Dean
 * @date 9/27/2026
 */
object TestNats {
    private val container = GenericContainer<Nothing>(DockerImageName.parse("nats:2")).apply {
        setCommand("--jetstream")
        addExposedPort(4222)
        setWaitStrategy(Wait.forLogMessage(".*Server is ready.*\\n", 1))
        start()
    }
    private val url = "nats://${container.host}:${container.getMappedPort(4222)}"
    private val nats = Nats(testConfig(mapOf("LIFTGATE_NATS_URL" to url)))
    private val streams = io.nats.client.Nats.connect(url).jetStreamManagement()

    fun clean(): Nats = nats.also {
        streams.streamNames.forEach(streams::deleteStream)
        it.ensureStream()
    }
}
