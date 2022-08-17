package io.liftgate.server.token

import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.lang3.RandomStringUtils
import java.util.*

/**
 * Generates/caches a random authentication token
 * used for authenticating RPCs.
 *
 * @author GrowlyX
 * @since 8/15/2022
 */
object TokenGenerator
{
    lateinit var cached: String

    fun generate(): String =
        DigestUtils.sha512Hex(
            UUID.randomUUID().toString()
        )
}
