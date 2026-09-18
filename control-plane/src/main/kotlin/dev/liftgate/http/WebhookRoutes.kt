package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.build.WebhookHandler
import dev.liftgate.build.Webhooks
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.jsonObject

fun Route.webhookRoutes(app: App) {
    val handler = WebhookHandler(app)
    post("/webhooks/github") {
        val secret = app.config.github?.webhookSecret ?: notFound("webhook")
        val body = call.receive<ByteArray>()
        if (!Webhooks.verify(secret, body, call.request.header("X-Hub-Signature-256"))) unauthorized()
        if (call.request.header("X-GitHub-Event") == "push") handler.handlePush(json.parseToJsonElement(body.decodeToString()).jsonObject)
        call.respond(HttpStatusCode.NoContent)
    }
}
