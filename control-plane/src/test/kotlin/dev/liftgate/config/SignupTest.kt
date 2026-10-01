package dev.liftgate.config

import dev.liftgate.minimalEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 9/27/2026
 */
class SignupTest {
    @Test
    fun `sign-up defaults to approval and normalises its allowlist`() {
        val config = Config.fromEnv(minimalEnv + ("LIFTGATE_SIGNUP_ALLOW" to " github:Dean, @Acme.dev ,ops@example.dev,"))
        assertEquals(Signup.APPROVAL, config.signup)
        assertEquals(listOf("github:dean", "@acme.dev", "ops@example.dev"), config.signupAllow)
        assertNull(config.termsUrl)
        assertEquals(Signup.OPEN, Config.fromEnv(minimalEnv + ("LIFTGATE_SIGNUP" to "Open")).signup)
    }

    @Test
    fun `an unknown mode or allowlist entry stops startup`() {
        assertTrue("LIFTGATE_SIGNUP" in assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_SIGNUP" to "invite")) }.message.orEmpty())
        assertTrue("dean" in assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_SIGNUP_ALLOW" to "dean")) }.message.orEmpty())
    }

    @Test
    fun `operators are lowercased github logins and emails, never domains`() {
        assertEquals(emptyList(), Config.fromEnv(minimalEnv).operators)
        assertEquals(listOf("github:octo-ops", "ops@example.com"), Config.fromEnv(minimalEnv + ("LIFTGATE_OPERATORS" to " github:Octo-Ops, Ops@Example.com ,")).operators)
        assertTrue("@example.com" in assertFailsWith<IllegalStateException> { Config.fromEnv(minimalEnv + ("LIFTGATE_OPERATORS" to "@example.com")) }.message.orEmpty())
    }
}
