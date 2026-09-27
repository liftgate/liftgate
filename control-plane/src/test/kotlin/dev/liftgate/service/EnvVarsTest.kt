package dev.liftgate.service

import dev.liftgate.TestDatabase
import dev.liftgate.http.LiftgateException
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.secret.SecretBox
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * @author Dean
 * @date 9/17/2026
 */
class EnvVarsTest {
    @Test
    fun `a secret keeps its value only while it stays secret`() = runBlocking {
        val db = TestDatabase.clean()
        val orgs = Orgs(db)
        val projects = Projects(db)
        val org = orgs.create("acme", "Acme", db.tx { insertUser("dean", null, null, null) }.id)
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
