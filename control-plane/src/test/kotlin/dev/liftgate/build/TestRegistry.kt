package dev.liftgate.build

import dev.liftgate.config.RegistryTokenConfig
import dev.liftgate.testConfig
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * @author Dean
 * @date 9/27/2026
 */
object TestRegistry {
    const val ADDRESS = "10.200.0.1:5050"
    val config = testConfig().copy(
        registry = ADDRESS,
        registryInsecure = true,
        registryTokenAuth = true,
        registryTokens = RegistryTokenConfig(TestKeys.privateKeyPem, TestKeys.certificatePem, "pull-password"),
        registryJanitorPassword = "janitor-password",
    )
    val container = GenericContainer<Nothing>(DockerImageName.parse("registry:2.8.3")).apply {
        withCopyToContainer(Transferable.of(File("../infra/registry/config.yml").readText()), "/etc/docker/registry/config.yml")
        withCopyToContainer(Transferable.of(TestKeys.certificatePem), "/etc/docker/registry/token.crt")
        withEnv("REGISTRY_HTTP_ADDR", ":5000")
        addExposedPort(5000)
        start()
    }
    private val http = HttpClient.newHttpClient()

    fun send(method: String, target: String, token: String, body: String? = null, type: String? = null): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI(if (target.startsWith("http")) target else "http://${container.host}:${container.getMappedPort(5000)}$target"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .header("Authorization", "Bearer $token")
            .header("Accept", manifestTypes)
            .apply { type?.let { header("Content-Type", it) } }
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
