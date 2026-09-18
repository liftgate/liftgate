plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "dev.liftgate"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://build.shibboleth.net/maven/releases/") {
        content { includeGroupByRegex("org\\.opensaml.*|net\\.shibboleth.*") }
    }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.hikari)
    implementation(libs.postgres)
    implementation(libs.jnats) { exclude(group = "org.bouncycastle", module = "bcprov-lts8on") }
    implementation(libs.hazelcast)
    implementation(libs.fabric8.client)
    implementation(libs.logback)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.micrometer.prometheus)
    implementation(libs.java.jwt)
    implementation(libs.bcpkix)
    implementation(libs.webauthn)
    implementation(libs.angus.mail)
    implementation(libs.opensaml.saml.impl)
    implementation(libs.opensaml.xmlsec.impl)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.fabric8.server.mock)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.liftgate.LiftgateKt"
}

tasks.test {
    useJUnitPlatform()
}
