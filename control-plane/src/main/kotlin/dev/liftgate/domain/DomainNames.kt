package dev.liftgate.domain

import dev.liftgate.http.invalid

private const val MAX_HOSTNAME = 253
private const val MAX_LABEL = 63
private val label = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

/**
 * @author Dean
 * @date 9/17/2026
 */
object DomainNames {
    fun platform(orgSlug: String, projectSlug: String, serviceSlug: String, deployDomain: String): String {
        val name = "$serviceSlug-$projectSlug-$orgSlug"
        if (name.length > MAX_LABEL) invalid("the service, project and organization slugs together exceed $MAX_LABEL characters")
        return "$name.$deployDomain".lowercase()
    }

    fun validate(hostname: String, deployDomain: String) {
        val labels = hostname.split('.')
        if (hostname.length > MAX_HOSTNAME || labels.size < 2 || labels.any { !label.matches(it) }) invalid("hostname is not a valid DNS name")
        if (hostname == deployDomain || hostname.endsWith(".$deployDomain")) invalid("hostnames under $deployDomain are assigned automatically")
    }
}
