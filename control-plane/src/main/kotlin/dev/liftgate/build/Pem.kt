package dev.liftgate.build

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey

fun rsaPrivateKey(pem: String, variable: String): RSAPrivateKey = when (val parsed = PEMParser(StringReader(pem.replace("\\n", "\n"))).use { it.readObject() }) {
    is PEMKeyPair -> JcaPEMKeyConverter().getPrivateKey(parsed.privateKeyInfo)
    is PrivateKeyInfo -> JcaPEMKeyConverter().getPrivateKey(parsed)
    else -> error("$variable must be a PEM encoded RSA private key")
} as RSAPrivateKey
