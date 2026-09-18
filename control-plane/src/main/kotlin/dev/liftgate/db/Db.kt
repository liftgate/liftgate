package dev.liftgate.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.liftgate.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

val Enum<*>.sql: String get() = name.lowercase()

inline fun <reified E : Enum<E>> String.toEnum(): E = enumValueOf(uppercase())

/**
 * @author Dean
 * @date 9/17/2026
 */
class Db(config: Config) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        maximumPoolSize = 10
    })
    private val database = Database.connect(dataSource)

    fun migrate() {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    suspend fun <T> tx(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(database) { block() } }

    override fun close() = dataSource.close()
}
