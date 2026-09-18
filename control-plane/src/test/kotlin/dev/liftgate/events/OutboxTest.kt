package dev.liftgate.events

import dev.liftgate.db.Db
import dev.liftgate.db.Outbox
import dev.liftgate.testConfig
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Dean
 * @date 9/17/2026
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxTest {
    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
    }

    @Test
    fun `relay publishes pending rows in order and marks them published`() = runBlocking {
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
        db.tx {
            enqueue(Subject.BUILD_REQUESTED, buildJsonObject { put("buildId", "b1") })
            enqueue(Subject.RELEASE_REQUESTED, buildJsonObject { put("deploymentId", "d1") })
        }
        val nats = mockk<Nats>(relaxed = true)
        val relay = OutboxRelay(db, nats)

        assertEquals(2, relay.runOnce())
        assertEquals(0, relay.runOnce())
        verifyOrder {
            nats.publish(Subject.BUILD_REQUESTED, any(), buildJsonObject { put("buildId", "b1") })
            nats.publish(Subject.RELEASE_REQUESTED, any(), buildJsonObject { put("deploymentId", "d1") })
        }
        assertEquals(0L, db.tx { Outbox.selectAll().where { Outbox.publishedAt.isNull() }.count() })
        db.close()
    }
}
