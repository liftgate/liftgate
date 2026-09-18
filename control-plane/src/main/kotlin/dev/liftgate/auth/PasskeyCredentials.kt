package dev.liftgate.auth

import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.data.ByteArray as Bytes
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import dev.liftgate.db.Passkeys as PasskeysTable
import dev.liftgate.db.now
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.nio.ByteBuffer
import java.util.Optional
import java.util.UUID

val UUID.userHandle: Bytes get() = Bytes(ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array())

val Bytes.userId: UUID? get() = bytes.takeIf { it.size == 16 }?.let { ByteBuffer.wrap(it).run { UUID(long, long) } }

/**
 * @author Dean
 * @date 9/18/2026
 */
object PasskeyCredentials : CredentialRepository {
    override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> = emptySet()

    override fun getUserHandleForUsername(username: String): Optional<Bytes> = Optional.empty()

    override fun getUsernameForUserHandle(userHandle: Bytes): Optional<String> = Optional.ofNullable(userHandle.userId?.toString())

    override fun lookup(credentialId: Bytes, userHandle: Bytes): Optional<RegisteredCredential> = Optional.ofNullable(
        userHandle.userId?.let { userId ->
            PasskeysTable.selectAll().where { (PasskeysTable.credentialId eq credentialId.bytes) and (PasskeysTable.userId eq userId) }
                .forUpdate(ForUpdateOption.ForUpdate).singleOrNull()?.toCredential()
        },
    )

    override fun lookupAll(credentialId: Bytes): Set<RegisteredCredential> =
        PasskeysTable.selectAll().where { PasskeysTable.credentialId eq credentialId.bytes }.map { it.toCredential() }.toSet()

    fun descriptors(userId: UUID): Set<PublicKeyCredentialDescriptor> = PasskeysTable.select(PasskeysTable.credentialId)
        .where { PasskeysTable.userId eq userId }
        .map { PublicKeyCredentialDescriptor.builder().id(Bytes(it[PasskeysTable.credentialId])).build() }.toSet()

    fun used(credentialId: Bytes, signatureCount: Long): UUID =
        PasskeysTable.updateReturning(listOf(PasskeysTable.userId), { PasskeysTable.credentialId eq credentialId.bytes }) {
            it[PasskeysTable.signatureCount] = signatureCount
            it[lastUsedAt] = now()
        }.single()[PasskeysTable.userId]

    private fun ResultRow.toCredential() = RegisteredCredential.builder()
        .credentialId(Bytes(this[PasskeysTable.credentialId]))
        .userHandle(this[PasskeysTable.userId].userHandle)
        .publicKeyCose(Bytes(this[PasskeysTable.publicKey]))
        .signatureCount(this[PasskeysTable.signatureCount])
        .build()
}
