package dev.liftgate.http

import dev.liftgate.testConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.cookie
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

fun HttpRequestBuilder.session(origin: String? = testConfig().dashboardUrl) {
    cookie(SESSION_COOKIE, "s")
    origin?.let { header(HttpHeaders.Origin, it) }
}
