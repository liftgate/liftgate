@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package dev.liftgate.auth

import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import com.yubico.webauthn.exception.AssertionFailedException
import com.yubico.webauthn.exception.RegistrationFailedException
import dev.liftgate.cache.Cache
import dev.liftgate.config.Config
import dev.liftgate.db.Db
import dev.liftgate.db.Passkeys as PasskeysTable
import dev.liftgate.http.InstantSerializer
import dev.liftgate.http.LiftgateException
import dev.liftgate.http.UuidSerializer
import dev.liftgate.org.User
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val PASSKEY = "passkey"
private const val CHALLENGE_MINUTES = 5L

/**
 * @author Dean
 * @date 9/18/2026
 */
@Serializable
data class Passkey(val id: UUID, val name: String, val createdAt: Instant, val lastUsedAt: Instant?)

fun relyingParty(config: Config, credentials: CredentialRepository): RelyingParty = RelyingParty.builder()
    .identity(RelyingPartyIdentity.builder().id(config.webauthnRpId).name(config.webauthnRpName).build())
    .credentialRepository(credentials)
    .origins(setOf(config.dashboardUrl))
    .build()

/**
 * @author Dean
 * @date 9/18/2026
 */
class Passkeys(config: Config, private val db: Db, private val cache: Cache, private val signIn: SignIn) {
    private val rp = relyingParty(config, PasskeyCredentials)

    suspend fun registrationOptions(user: User): Pair<String, String> {
        val options = rp.startRegistration(
            StartRegistrationOptions.builder()
                .user(UserIdentity.builder().name(user.email ?: user.login).displayName(user.name ?: user.login).id(user.id.userHandle).build())
                .authenticatorSelection(
                    AuthenticatorSelectionCriteria.builder().residentKey(ResidentKeyRequirement.REQUIRED).userVerification(UserVerificationRequirement.PREFERRED).build(),
                )
                .build(),
        ).toBuilder().excludeCredentials(db.tx { PasskeyCredentials.descriptors(user.id) }).build()
        return remember(options.toJson()) to options.toCredentialsCreateJson()
    }

    suspend fun register(user: User, challenge: String?, credential: String, name: String): Passkey {
        val options = verified {
            FinishRegistrationOptions.builder()
                .request(PublicKeyCredentialCreationOptions.fromJson(take(challenge)).also { require(it.user.id == user.id.userHandle) })
                .response(PublicKeyCredential.parseRegistrationResponseJson(credential))
                .build()
        }
        return db.tx {
            val result = verified { rp.finishRegistration(options) }
            audit(user.id, "auth.link", PASSKEY)
            PasskeysTable.insertReturning {
                it[id] = UUID.randomUUID()
                it[userId] = user.id
                it[credentialId] = result.keyId.id.bytes
                it[publicKey] = result.publicKeyCose.bytes
                it[signatureCount] = result.signatureCount
                it[PasskeysTable.name] = name
            }.single().toPasskey()
        }
    }

    fun assertionOptions(): Pair<String, String> =
        rp.startAssertion(StartAssertionOptions.builder().userVerification(UserVerificationRequirement.PREFERRED).build())
            .let { remember(it.toJson()) to it.toCredentialsGetJson() }

    suspend fun verify(challenge: String?, credential: String): SignedIn {
        val options = verified {
            FinishAssertionOptions.builder()
                .request(AssertionRequest.fromJson(take(challenge)))
                .response(PublicKeyCredential.parseAssertionResponseJson(credential))
                .build()
        }
        val userId = db.tx { verified { rp.finishAssertion(options) }.let { PasskeyCredentials.used(it.credential.credentialId, it.signatureCount) } }
        return signIn.complete(userId, PASSKEY)
    }

    suspend fun list(userId: UUID): List<Passkey> = db.tx {
        PasskeysTable.selectAll().where { PasskeysTable.userId eq userId }.orderBy(PasskeysTable.createdAt).map { it.toPasskey() }
    }

    suspend fun delete(userId: UUID, id: UUID) = db.tx {
        removeSignInMethod(userId) { PasskeysTable.deleteWhere { (PasskeysTable.id eq id) and (PasskeysTable.userId eq userId) } }
    }

    private fun remember(request: String) = randomToken().also { cache.passkeyChallenges.set(it, request, CHALLENGE_MINUTES, TimeUnit.MINUTES) }

    private fun take(id: String?) = id?.let { cache.passkeyChallenges.remove(it) } ?: invalidPasskey()

    private fun ResultRow.toPasskey() =
        Passkey(this[PasskeysTable.id], this[PasskeysTable.name], this[PasskeysTable.createdAt].toInstant(), this[PasskeysTable.lastUsedAt]?.toInstant())
}

private inline fun <T> verified(block: () -> T): T = try {
    block()
} catch (e: Exception) {
    when (e) {
        is RegistrationFailedException, is AssertionFailedException, is IOException, is IllegalArgumentException -> invalidPasskey()
        else -> throw e
    }
}

private fun invalidPasskey(): Nothing = throw LiftgateException(HttpStatusCode.BadRequest, "invalid_passkey", "the passkey could not be verified")
