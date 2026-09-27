package dev.liftgate

import dev.liftgate.events.Nats
import io.nats.client.Connection
import io.nats.client.api.StreamConfiguration
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

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
    private val nats = Nats(testConfig(mapOf("LIFTGATE_NATS_URL" to url)), Duration.ofMillis(10))
    val connection: Connection = io.nats.client.Nats.connect(url)
    val streams = connection.jetStreamManagement()

    fun clean(): Nats = nats.also {
        streams.streamNames.forEach(streams::deleteStream)
        it.ensureStream()
    }

    fun addSubject(subject: String) {
        streams.updateStream(StreamConfiguration.builder(streams.getStreamInfo("LIFTGATE").configuration).addSubjects(subject).build())
    }
}
