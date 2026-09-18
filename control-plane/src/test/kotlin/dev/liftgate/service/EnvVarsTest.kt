package dev.liftgate.service

import dev.liftgate.db.Db
import dev.liftgate.http.LiftgateException
import dev.liftgate.org.Orgs
import dev.liftgate.project.Projects
import dev.liftgate.secret.SecretBox
import dev.liftgate.testConfig
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * @author Dean
 * @date 9/17/2026
 */
@Testcontainers(disabledWithoutDocker = true)
class EnvVarsTest {
    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
    }

    @Test
    fun `a secret keeps its value only while it stays secret`() = runBlocking {
        val db = Db(
            testConfig(
                mapOf(
                    "LIFTGATE_DATABASE_URL" to postgres.jdbcUrl,
                    "LIFTGATE_DATABASE_USER" to postgres.username,
                    "LIFTGATE_DATABASE_PASSWORD" to postgres.password,
                ),
            ),
        )
        db.migrate()
        val orgs = Orgs(db)
        val projects = Projects(db)
        val org = orgs.create("acme", "Acme", orgs.upsertUser(1, "dean", null, null, null).id)
        val project = projects.create(org.id, "shop", "Shop", "acme/shop", 42)
        val service = Services(db).create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
        val envVars = EnvVars(db, SecretBox(ByteArray(32)))

        envVars.replace(service.id, listOf(EnvVar("KEY", "hunter2", secret = true)))
        envVars.replace(service.id, listOf(EnvVar("KEY", null, secret = true)))
        assertEquals(listOf(EnvVar("KEY", "hunter2", secret = true)), envVars.list(service.id, reveal = true))
        assertEquals(listOf(EnvVar("KEY", null, secret = true)), envVars.list(service.id, reveal = false))
        assertFailsWith<LiftgateException> { envVars.replace(service.id, listOf(EnvVar("KEY", null, secret = false))) }
        assertEquals(listOf(EnvVar("KEY", "hunter2", secret = true)), envVars.list(service.id, reveal = true))
    }
}
