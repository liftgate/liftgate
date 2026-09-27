package dev.liftgate.domain

import dev.liftgate.http.invalid
import dev.liftgate.service.ServiceScope
import java.math.BigInteger
import java.security.MessageDigest

private const val MAX_HOSTNAME = 253
private const val MAX_LABEL = 63
private const val SUFFIX = 6
private val label = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

/**
 * @author Dean
 * @date 9/17/2026
 */
object DomainNames {
    const val MAX_DEPLOY_DOMAIN = MAX_HOSTNAME - MAX_LABEL - 1

    fun platform(scope: ServiceScope, deployDomain: String): List<String> {
        val (service, environment, project, org) = scope
        val readable = listOfNotNull(service.slug, environment.slug.takeUnless { it == "production" }, project.slug, org.slug).joinToString("-")
        val suffix = BigInteger(1, MessageDigest.getInstance("SHA-256").digest(service.id.toString().toByteArray())).toString(36).takeLast(SUFFIX)
        val fallback = "${service.slug}-${project.slug}".take(MAX_LABEL - SUFFIX - 1).trimEnd('-') + "-$suffix"
        val unique = service.id.toString().replace("-", "")
        return listOfNotNull(readable.takeIf { it.length <= MAX_LABEL }, fallback, unique).map { "$it.$deployDomain".lowercase() }
    }

    fun validate(hostname: String, deployDomain: String) {
        val labels = hostname.split('.')
        if (hostname.length > MAX_HOSTNAME || labels.size < 2 || labels.any { !label.matches(it) }) invalid("hostname is not a valid DNS name")
        if (hostname == deployDomain || hostname.endsWith(".$deployDomain")) invalid("hostnames under $deployDomain are assigned automatically")
    }
}
