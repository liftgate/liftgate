package dev.liftgate.db

import dev.liftgate.TestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Dean
 * @date 9/27/2026
 */
class SchemaDriftTest {
    private val tables = requireNotNull(File(Users::class.java.protectionDomain.codeSource.location.toURI()).resolve("dev/liftgate/db").listFiles())
        .filter { it.extension == "class" }
        .map { Class.forName("dev.liftgate.db.${it.nameWithoutExtension}") }
        .filter { Table::class.java.isAssignableFrom(it) }
        .map { it.getField("INSTANCE").get(null) as Table }

    @Test
    fun `migrations create exactly the schema Tables kt declares`() = runBlocking {
        val db = TestDatabase.clean()
        assertEquals(emptyList(), db.tx { MigrationUtils.statementsRequiredForDatabaseMigration(*tables.toTypedArray(), withLogs = false) })
    }
}
