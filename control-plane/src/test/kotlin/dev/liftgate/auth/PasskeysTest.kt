package dev.liftgate.auth

import com.yubico.webauthn.AssertionResult
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.data.ByteArray as Bytes
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.exception.AssertionFailedException
import dev.liftgate.cache.Cache
import dev.liftgate.db.Db
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.json
import dev.liftgate.org.User
import dev.liftgate.testConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Optional
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/18/2026
 */
class PasskeysTest {
    companion object {
        private val cache = Cache(testConfig())

        @AfterAll
        @JvmStatic
        fun close() = cache.close()
    }

    private val user = User(UUID.randomUUID(), "dean", "Dean", "dean@liftgate.dev", null)
    private val existing = Bytes(Random.nextBytes(16))
    private val db = mockk<Db> { coEvery { tx<Set<PublicKeyCredentialDescriptor>>(any()) } returns setOf(PublicKeyCredentialDescriptor.builder().id(existing).build()) }
    private val passkeys = Passkeys(testConfig(), db, cache, mockk())
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val credentialId = Bytes(Random.nextBytes(16))

    private fun publicKey(body: String) = json.parseToJsonElement(body).jsonObject.getValue("publicKey").jsonObject

    private fun JsonObject.text(vararg path: String): String? =
        path.dropLast(1).fold(this) { node, name -> node.getValue(name).jsonObject }[path.last()]?.jsonPrimitive?.content

    @Test
    fun `registration options require a discoverable credential and exclude existing ones`() = runBlocking {
        val (challenge, body) = passkeys.registrationOptions(user)
        val options = publicKey(body)
        assertEquals("localhost", options.text("rp", "id"))
        assertEquals("Liftgate", options.text("rp", "name"))
        assertEquals(user.id.userHandle.base64Url, options.text("user", "id"))
        assertEquals("dean@liftgate.dev", options.text("user", "name"))
        assertEquals("required", options.text("authenticatorSelection", "residentKey"))
        assertEquals("preferred", options.text("authenticatorSelection", "userVerification"))
        assertEquals(listOf(existing.base64Url), options.getValue("excludeCredentials").jsonArray.map { it.jsonObject.text("id") })
        assertTrue(cache.passkeyChallenges.containsKey(challenge))
    }

    @Test
    fun `request options leave the choice of credential to the authenticator`() {
        val options = publicKey(passkeys.assertionOptions().second)
        assertNull(options["allowCredentials"])
        assertEquals("localhost", options.text("rpId"))
        assertEquals("preferred", options.text("userVerification"))
        assertTrue(Bytes.fromBase64Url(options.text("challenge")!!).size() >= 16)
    }

    @Test
    fun `challenges expire after five minutes and are single use`() = runBlocking {
        val (challenge) = passkeys.assertionOptions()
        assertEquals(300_000L, cache.passkeyChallenges.getEntryView(challenge)!!.ttl)
        assertEquals("invalid_passkey", assertFailsWith<LiftgateException> { passkeys.verify(challenge, "{}") }.code)
        assertFalse(cache.passkeyChallenges.containsKey(challenge))
        assertEquals("invalid_passkey", assertFailsWith<LiftgateException> { passkeys.verify(challenge, "{}") }.code)
        assertEquals("invalid_passkey", assertFailsWith<LiftgateException> { passkeys.verify(null, "{}") }.code)
    }

    @Test
    fun `a signature counter that does not advance is rejected`() {
        assertEquals(6L, assertion(stored = 5, counter = 6).signatureCount)
        assertEquals(0L, assertion(stored = 0, counter = 0).signatureCount)
        assertFailsWith<AssertionFailedException> { assertion(stored = 5, counter = 5) }
        assertFailsWith<AssertionFailedException> { assertion(stored = 5, counter = 0) }
    }

    private fun assertion(stored: Long, counter: Int): AssertionResult {
        val rp = relyingParty(testConfig(), credentials(stored))
        val request = rp.startAssertion(StartAssertionOptions.builder().build())
        val clientData = """{"type":"webauthn.get","challenge":"${request.publicKeyCredentialRequestOptions.challenge.base64Url}","origin":"http://localhost:3000"}"""
            .toByteArray()
        val authenticatorData = sha256("localhost".toByteArray()) + byteArrayOf(0x05) + ByteBuffer.allocate(4).putInt(counter).array()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(authenticatorData + sha256(clientData))
            sign()
        }
        val response = """{"id":"${credentialId.base64Url}","rawId":"${credentialId.base64Url}","type":"public-key","clientExtensionResults":{},
            "response":{"clientDataJSON":"${Bytes(clientData).base64Url}","authenticatorData":"${Bytes(authenticatorData).base64Url}",
            "signature":"${Bytes(signature).base64Url}","userHandle":"${user.id.userHandle.base64Url}"}}"""
        return rp.finishAssertion(FinishAssertionOptions.builder().request(request).response(PublicKeyCredential.parseAssertionResponseJson(response)).build())
    }

    private fun credentials(stored: Long): CredentialRepository {
        val credential = RegisteredCredential.builder()
            .credentialId(credentialId)
            .userHandle(user.id.userHandle)
            .publicKeyEs256Raw(Bytes(key.public.encoded.takeLast(65).toByteArray()))
            .signatureCount(stored)
            .build()
        return object : CredentialRepository by PasskeyCredentials {
            override fun lookup(credentialId: Bytes, userHandle: Bytes) = Optional.of(credential)

            override fun lookupAll(credentialId: Bytes) = setOf(credential)
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
