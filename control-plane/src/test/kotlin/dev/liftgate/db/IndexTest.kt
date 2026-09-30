package dev.liftgate.db

import dev.liftgate.TestDatabase
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime
import dev.liftgate.db.Services as ServicesTable

/**
 * @author Dean
 * @date 9/30/2026
 */
class IndexTest {
    private val db = TestDatabase.clean()

    @Test
    fun `every foreign key leads an index`() = runBlocking {
        val unindexed = db.tx {
            exec(
                """
                select c.conrelid::regclass::text || '.' || c.conname from pg_constraint c
                where c.contype = 'f' and not exists (
                    select from pg_index i
                    where i.indrelid = c.conrelid and i.indpred is null
                      and (select array_agg(i.indkey[k]) from generate_series(0, cardinality(c.conkey) - 1) k) @> c.conkey
                )
                order by 1
                """,
            ) { generateSequence { if (it.next()) it.getString(1) else null }.toList() }
        }
        assertEquals(emptyList(), unindexed)
    }

    @Test
    fun `deleting a service beside a million usage rows takes under 50 ms`() = runBlocking {
        val owner = db.tx { insertUser("dean", null, null, null) }
        val org = Orgs(db).create("acme", "Acme", owner.id)
        val environment = Projects(db).let { it.environments(it.create(org.id, "shop", "Shop", "acme/shop", 42).id).single() }
        val (gone, kept) = listOf("web", "worker").map { Services(db).create(environment.id, ServiceSpec(it, it, ServiceKind.WORKER)) }
        db.tx {
            exec("set local session_replication_role = replica")
            exec(
                """
                insert into usage_records (org_id, service_id, metric, quantity, window_start, window_end)
                select ?::uuid, (case when n <= 1440 then ? else ? end)::uuid, 'cpu', 1, now(), now() from generate_series(1, 1000000) n
                """,
                listOf(org.id, gone.id, kept.id).map { TextColumnType() to it.toString() },
            )
        }
        db.tx { exec("analyze usage_records") }
        val took = measureTime { db.tx { ServicesTable.deleteWhere { ServicesTable.id eq gone.id } } }
        assertTrue(took < 50.milliseconds, "deleting a service took $took")
    }
}
