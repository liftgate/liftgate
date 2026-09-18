package dev.liftgate.auth

import dev.liftgate.cache.Cache
import dev.liftgate.db.Db
import dev.liftgate.db.EmailCodes as EmailCodesTable
import dev.liftgate.db.now
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.invalid
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val EMAIL = "email"
private const val CODE_MINUTES = 10L
const val MAX_ATTEMPTS = 5
const val STARTS_PER_EMAIL = 5
const val STARTS_PER_IP = 20
private const val HMAC = "HmacSHA256"
private val emailAddress = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

/**
 * @author Dean
 * @date 9/18/2026
 */
enum class CodeCheck { MATCH, WRONG, SPENT }

fun checkCode(stored: ByteArray, attempts: Int, expiresAt: Instant, candidate: ByteArray, now: Instant = Instant.now()) = when {
    !now.isBefore(expiresAt) -> CodeCheck.SPENT
    MessageDigest.isEqual(stored, candidate) -> CodeCheck.MATCH
    attempts + 1 >= MAX_ATTEMPTS -> CodeCheck.SPENT
    else -> CodeCheck.WRONG
}

fun normalizeEmail(email: String) = email.trim().lowercase().takeIf { it.length <= 254 && emailAddress.matches(it) } ?: invalid("a valid email address is required")

/**
 * @author Dean
 * @date 9/18/2026
 */
class EmailCodes(private val db: Db, private val cache: Cache, masterKey: ByteArray, private val mailer: Mailer, private val signIn: SignIn) {
    private val key = SecretKeySpec(mac(SecretKeySpec(masterKey, HMAC)).doFinal("liftgate email codes".toByteArray()), HMAC)
    private val random = SecureRandom()

    suspend fun start(email: String, clientIp: String) {
        val address = normalizeEmail(email)
        if (!cache.allow("email-start-ip:$clientIp", STARTS_PER_IP) || !cache.allow("email-start:$address", STARTS_PER_EMAIL)) {
            throw LiftgateException(HttpStatusCode.TooManyRequests, "rate_limited", "too many codes requested, try again later")
        }
        val code = "%06d".format(random.nextInt(1_000_000))
        db.tx {
            EmailCodesTable.deleteWhere { expiresAt lessEq now() }
            EmailCodesTable.upsert {
                it[EmailCodesTable.email] = address
                it[codeHash] = hash(address, code)
                it[attempts] = 0
                it[expiresAt] = now().plusMinutes(CODE_MINUTES)
            }
        }
        withContext(Dispatchers.IO) { mailer.sendCode(address, code, CODE_MINUTES) }
    }

    suspend fun verify(email: String, code: String): SignedIn {
        val address = normalizeEmail(email)
        val candidate = hash(address, code)
        val check = db.tx {
            EmailCodesTable.selectAll().where { EmailCodesTable.email eq address }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
                ?.let { checkCode(it[EmailCodesTable.codeHash], it[EmailCodesTable.attempts], it[EmailCodesTable.expiresAt].toInstant(), candidate) }
                ?.also { spend(address, it) }
        }
        if (check != CodeCheck.MATCH) throw LiftgateException(HttpStatusCode.BadRequest, "invalid_code", "the code is wrong or has expired")
        return signIn.complete(VerifiedIdentity(EMAIL, address, address, emailVerified = true))
    }

    fun hash(email: String, code: String): ByteArray = mac(key).doFinal("$email:$code".toByteArray())

    private fun spend(address: String, check: CodeCheck) = when (check) {
        CodeCheck.WRONG -> EmailCodesTable.update({ EmailCodesTable.email eq address }) { it[attempts] = attempts + 1 }
        else -> EmailCodesTable.deleteWhere { email eq address }
    }
}

private fun mac(key: SecretKeySpec) = Mac.getInstance(HMAC).apply { init(key) }
