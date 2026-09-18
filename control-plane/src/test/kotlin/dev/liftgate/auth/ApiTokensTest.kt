package dev.liftgate.auth

import dev.liftgate.db.Db
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/17/2026
 */
class ApiTokensTest {
    @Test
    fun `hash is deterministic and differs per token`() {
        val hash = ApiTokens.hash("lg_abc")
        assertEquals(hash, ApiTokens.hash("lg_abc"))
        assertNotEquals(hash, ApiTokens.hash("lg_abd"))
        assertEquals(43, hash.length)
    }

    @Test
    fun `create returns a prefixed random token`() = runBlocking {
        val db = mockk<Db>()
        coEvery { db.tx(any<JdbcTransaction.() -> Any?>()) } returns null
        val tokens = ApiTokens(db)
        val first = tokens.create(UUID.randomUUID(), "ci", UUID.randomUUID())
        val second = tokens.create(UUID.randomUUID(), "ci", UUID.randomUUID())
        assertTrue(first.startsWith("lg_"))
        assertEquals(46, first.length)
        assertNotEquals(first, second)
    }
}
