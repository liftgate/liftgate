package io.liftgate.server.token

import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.lang3.RandomStringUtils

/**
 * Generates/caches a random authentication token
 * used for authenticating RPC interactions.
 *
 * @author GrowlyX
 * @since 8/15/2022
 */
object TokenGenerator
{
    lateinit var cached: String

    fun generate(): String =
        DigestUtils.sha256Hex(
            RandomStringUtils.randomAlphanumeric(40)
        )
}
