package dev.liftgate.db

import dev.liftgate.TestDatabase
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import kotlinx.coroutines.runBlocking
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/27/2026
 */
class ReservedOrgSlugsTest {
    private val check = requireNotNull(javaClass.getResource("/db/migration/V10__reserved_org_slugs.sql")).readText()
    private val retention = requireNotNull(javaClass.getResource("/db/migration/V15__registry_retention.sql")).readText()

    @Test
    fun `the migration stops when an organization already holds a reserved slug`() = runBlocking {
        val db = TestDatabase.clean()
        db.tx { exec(check) }
        Orgs(db).create("docs", "Docs", db.tx { insertUser("dean", null, null, null) }.id)
        val error = assertFailsWith<SQLException> { db.tx { exec(check) } }
        assertTrue("slugs docs are now reserved" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `the registry retention migration stops when an organization is named liftgate`() = runBlocking {
        val db = TestDatabase.clean()
        Orgs(db).create("liftgate", "Liftgate", db.tx { insertUser("dean", null, null, null) }.id)
        val error = assertFailsWith<SQLException> { db.tx { exec(retention) } }
        assertTrue("slug liftgate is now reserved" in error.message.orEmpty(), error.message)
    }
}
