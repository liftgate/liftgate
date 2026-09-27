package dev.liftgate.build

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * @author Dean
 * @date 9/27/2026
 */
object TestKeys {
    val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val privateKeyPem = pem("PRIVATE KEY", pair.private.encoded)
    val certificatePem: String = Instant.now().let { now ->
        val name = X500Name("CN=liftgate")
        val certificate = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date.from(now.minusSeconds(3600)), Date.from(now.plusSeconds(86400)), name, pair.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        pem("CERTIFICATE", certificate.encoded)
    }

    private fun pem(type: String, der: ByteArray) =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"
}
