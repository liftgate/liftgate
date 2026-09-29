package dev.liftgate.http

import dev.liftgate.App
import dev.liftgate.config.Config
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.netty.util.NetUtil
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.util.UUID

val orgScopedPaths = listOf("/orgs", "/projects", "/environments", "/services", "/deployments", "/domains", "/invitations")

fun Route.apiRoutes(app: App) {
    healthRoutes(app)
    route("/api/v1") {
        authRoutes(app)
        passkeyRoutes(app)
        emailRoutes(app)
        ssoRoutes(app)
        orgRoutes(app)
        projectRoutes(app)
        serviceRoutes(app)
        deployRoutes(app)
        domainRoutes(app)
        logRoutes(app)
        webhookRoutes(app)
        registryRoutes(app)
        rateLimits(app)
        notificationRoutes(app)
        githubRoutes(app)
        audit(app)
    }
}

fun ApplicationCall.uuid(name: String): UUID = parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    ?: throw LiftgateException(HttpStatusCode.BadRequest, "bad_request", "$name must be a UUID")

fun ApplicationCall.clientIp(config: Config): String {
    val peer = request.origin.remoteAddress
    val header = config.clientIpHeader?.takeIf { config.trustedProxyCidrs.any { it.matches(InetSocketAddress(peer, 0)) } }
    val ip = header?.let { request.headers[it]?.trim() }?.takeIf { it.isNotEmpty() }
        ?: request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty().flatMap { it.split(',') }.map(String::trim)
            .let { hops -> hops.getOrNull(hops.size - config.trustedProxies) }?.takeIf { it.isNotEmpty() } ?: peer
    return (NetUtil.createInetAddressFromIpAddressString(ip) as? Inet6Address)?.let { it.address.toHexString(0, 8) + "/64" } ?: ip
}
