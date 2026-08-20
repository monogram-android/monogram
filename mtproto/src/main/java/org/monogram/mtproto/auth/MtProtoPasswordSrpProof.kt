package org.monogram.mtproto.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.mtproto.crypto.EntropySource
import org.monogram.mtproto.crypto.SecureEntropySource
import org.monogram.mtproto.crypto.TelegramPasswordSrp
import org.monogram.mtproto.tl.generated.cloud.layer223.InputCheckPasswordSrp_1e0a258433
import org.monogram.mtproto.tl.generated.cloud.layer223.InputCheckPasswordSrp_5100d694df
import org.monogram.mtproto.tl.generated.cloud.layer223.PasswordKdfAlgoSha256Sha256Pbkdf2Hmacsha512Iter100000Sha256ModPow
import org.monogram.mtproto.tl.generated.cloud.layer223.account.Password_ac67a26d5c
import org.monogram.mtproto.tl.runtime.TlBytes

/** Creates a one-use SRP proof for cloud methods that require account password confirmation. */
object MtProtoPasswordSrpProof {
    suspend fun create(
        password: String,
        configuration: Password_ac67a26d5c,
    ): InputCheckPasswordSrp_1e0a258433 = create(password, configuration, SecureEntropySource)

    internal suspend fun create(
        password: String,
        configuration: Password_ac67a26d5c,
        entropy: EntropySource,
    ): InputCheckPasswordSrp_1e0a258433 {
        require(password.isNotBlank()) { "password must not be blank" }
        check(configuration.hasPassword) { "MTProto account has no password challenge" }
        val algorithm = configuration.currentAlgo as?
            PasswordKdfAlgoSha256Sha256Pbkdf2Hmacsha512Iter100000Sha256ModPow
            ?: error("Unsupported MTProto password KDF")
        val serverB = configuration.srpB?.toByteArray() ?: error("MTProto password challenge is missing srpB")
        val srpId = configuration.srpId ?: error("MTProto password challenge is missing srpId")
        val salt1 = algorithm.salt1.toByteArray()
        val salt2 = algorithm.salt2.toByteArray()
        val prime = algorithm.p.toByteArray()
        val proof = try {
            withContext(Dispatchers.Default) {
                TelegramPasswordSrp.createProof(
                    password = password,
                    salt1 = salt1,
                    salt2 = salt2,
                    generator = algorithm.g,
                    primeBytes = prime,
                    serverBBytes = serverB,
                    srpId = srpId,
                    entropy = entropy,
                )
            }
        } finally {
            salt1.fill(0)
            salt2.fill(0)
            prime.fill(0)
            serverB.fill(0)
        }
        return try {
            InputCheckPasswordSrp_5100d694df(
                srpId = proof.srpId,
                a = TlBytes.copyOf(proof.a),
                m1 = TlBytes.copyOf(proof.m1),
            )
        } finally {
            proof.a.fill(0)
            proof.m1.fill(0)
        }
    }
}
