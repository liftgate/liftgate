package dev.liftgate.auth

import dev.liftgate.cache.Cache
import dev.liftgate.testConfig
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Dean
 * @date 9/27/2026
 */
class SessionEvictionTest {
    companion object {
        private val cache by lazy { Cache(testConfig()) }

        @AfterAll
        @JvmStatic
        fun close() = cache.close()
    }

    @Test
    fun `evicting a user drops only their cached sessions`() {
        val suspended = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val ids = List(3) { randomToken() }
        cache.sessions.putAll(ids.zip(listOf(suspended, suspended, other)).toMap())
        Sessions(mockk(), cache, mockk()).evict(UUID.fromString(suspended))
        assertEquals(listOf(null, null, other), ids.map { cache.sessions[it] })
    }
}
