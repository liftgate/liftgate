package dev.liftgate.secret

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val NONCE_BYTES = 12
private const val TAG_BITS = 128

/**
 * @author Dean
 * @date 9/17/2026
 */
class SecretBox(key: ByteArray) {
    init {
        require(key.size == 32) { "master key must be 32 bytes" }
    }

    private val key = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    fun seal(plain: String): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce)) }
        return nonce + cipher.doFinal(plain.toByteArray())
    }

    fun open(sealed: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES)) }
        return String(cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES))
    }
}
