package dev.liftgate.secret

import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * @author Dean
 * @date 9/17/2026
 */
class SecretBoxTest {
    private val box = SecretBox(ByteArray(32) { it.toByte() })

    @Test
    fun `round trip`() = assertEquals("DATABASE_URL=postgres://x", box.open(box.seal("DATABASE_URL=postgres://x")))

    @Test
    fun `each seal uses a fresh nonce`() = assertFalse(box.seal("same").contentEquals(box.seal("same")))

    @Test
    fun `tampering fails`() {
        val sealed = box.seal("secret")
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        assertFailsWith<AEADBadTagException> { box.open(sealed) }
    }

    @Test
    fun `a different key cannot open`() {
        assertFailsWith<AEADBadTagException> { SecretBox(ByteArray(32)).open(box.seal("secret")) }
    }

    @Test
    fun `key must be 32 bytes`() {
        assertFailsWith<IllegalArgumentException> { SecretBox(ByteArray(16)) }
    }
}
