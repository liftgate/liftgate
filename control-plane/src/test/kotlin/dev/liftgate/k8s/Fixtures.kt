package dev.liftgate.k8s

import dev.liftgate.deploy.Build
import dev.liftgate.deploy.BuildStatus
import dev.liftgate.deploy.Deployment
import dev.liftgate.deploy.DeploymentStatus
import dev.liftgate.domain.Domain
import dev.liftgate.domain.DomainKind
import dev.liftgate.org.Organization
import dev.liftgate.project.Environment
import dev.liftgate.project.EnvironmentKind
import dev.liftgate.project.Project
import dev.liftgate.project.namespaceFor
import dev.liftgate.service.BuildStrategy
import dev.liftgate.service.EnvVar
import dev.liftgate.service.Service
import dev.liftgate.service.ServiceKind
import java.time.Instant
import java.util.UUID

val testOrg = Organization(UUID.randomUUID(), "acme", "Acme", "free")
val testProject = Project(UUID.randomUUID(), testOrg.id, "shop", "Shop", "acme/shop", "main", 42)
val testEnvironment = UUID.randomUUID().let { Environment(it, testProject.id, "production", "Production", EnvironmentKind.PRODUCTION, "main", namespaceFor(it)) }
val testService = Service(UUID.randomUUID(), testEnvironment.id, "api", "API", ServiceKind.WEB, "/", BuildStrategy.AUTO, "Dockerfile", 3000, 2, 250, 256, null, null)
val testBuild = Build(UUID.randomUUID(), testService.id, "abc123", "ship it", "main", BuildStatus.SUCCEEDED, "registry.test/acme/shop-api:abc123", null, null, null, Instant.now())
val testDeployment = Deployment(UUID.randomUUID(), testService.id, testBuild.id, DeploymentStatus.PENDING, 0, null, Instant.now())

fun testDomain(hostname: String, kind: DomainKind) = Domain(UUID.randomUUID(), testService.id, hostname, kind, null, Instant.now(), "pending")

fun testRelease(service: Service = testService) = Release(
    testDeployment,
    testBuild,
    service,
    testEnvironment,
    testProject,
    testOrg,
    listOf(EnvVar("DATABASE_URL", "postgres://db", secret = true), EnvVar("MODE", "production")),
    listOf(testDomain("api-shop-acme.liftgate.app", DomainKind.PLATFORM), testDomain("api.acme.dev", DomainKind.CUSTOM)),
)
