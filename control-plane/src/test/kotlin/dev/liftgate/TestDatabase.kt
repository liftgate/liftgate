package dev.liftgate

import dev.liftgate.db.Db
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * @author Dean
 * @date 9/27/2026
 */
object TestDatabase {
    val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine")).apply {
        setCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements", "-c", "pg_stat_statements.track_utility=off")
        start()
    }
    val config = testConfig(
        mapOf(
            "LIFTGATE_DATABASE_URL" to postgres.jdbcUrl,
            "LIFTGATE_DATABASE_USER" to postgres.username,
            "LIFTGATE_DATABASE_PASSWORD" to postgres.password,
        ),
    )
    private val db = Db(config).also { it.migrate() }

    fun clean(): Db = db.also {
        runBlocking {
            it.tx {
                exec(
                    """
                    do $$ begin
                        execute (select 'truncate ' || string_agg(quote_ident(tablename), ', ') || ' restart identity cascade'
                                 from pg_tables where schemaname = 'public' and tablename <> 'flyway_schema_history');
                    end $$
                    """,
                )
            }
        }
    }
}
