package org.monogram.mtproto.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.mtproto.crypto.EntropySource
import org.monogram.mtproto.crypto.SecureEntropySource
import org.monogram.mtproto.crypto.TelegramPasswordSrp
import org.monogram.mtproto.tl.generated.cloud.layer223.EmailVerificationCode
import org.monogram.mtproto.tl.generated.cloud.layer223.InputCheckPasswordSrp_5100d694df
import org.monogram.mtproto.tl.generated.cloud.layer223.PasswordKdfAlgoSha256Sha256Pbkdf2Hmacsha512Iter100000Sha256ModPow
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_fb610807ca
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetPassword
import org.monogram.mtproto.tl.generated.cloud.layer223.account.Password_ac67a26d5c
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_fb75ff221f
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.CheckPassword
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ResendCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SendCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCode_250764ccd9
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SignIn
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SignUp
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.transport.MtProtoRpcException
import org.monogram.mtproto.transport.MtProtoRpcTransport

data class MtProtoPasswordChallengeInfo(
    val hint: String?,
    val hasRecoveryEmail: Boolean,
)

interface MtProtoAuthorizationApi {
    suspend fun sendCode(
        phoneNumber: String,
        settings: CodeSettings_fb610807ca,
        apiId: Int,
        apiHash: String,
    ): SentCode_250764ccd9

    suspend fun resendCode(phoneNumber: String, phoneCodeHash: String, reason: String? = null): SentCode_250764ccd9

    suspend fun signIn(phoneNumber: String, phoneCodeHash: String, phoneCode: String): Authorization_fb75ff221f

    suspend fun signInWithEmailCode(
        phoneNumber: String,
        phoneCodeHash: String,
        emailCode: String,
    ): Authorization_fb75ff221f

    suspend fun signUp(
        phoneNumber: String,
        phoneCodeHash: String,
        firstName: String,
        lastName: String,
    ): Authorization_fb75ff221f

    suspend fun getPasswordChallengeInfo(): MtProtoPasswordChallengeInfo

    suspend fun checkPassword(password: String): Authorization_fb75ff221f
}

/** Typed cloud-auth requests over an already initialized MTProto RPC transport. */
class MtProtoAuthorizationClient internal constructor(
    private val transport: MtProtoRpcTransport,
    private val entropy: EntropySource,
) : MtProtoAuthorizationApi {
    constructor(transport: MtProtoRpcTransport) : this(transport, SecureEntropySource)

    override suspend fun sendCode(
        phoneNumber: String,
        settings: CodeSettings_fb610807ca,
        apiId: Int,
        apiHash: String,
    ): SentCode_250764ccd9 {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank" }
        require(apiId > 0) { "apiId must be positive" }
        require(apiHash.isNotBlank()) { "apiHash must not be blank" }
        return transport.execute(SendCode(phoneNumber, apiId, apiHash, settings))
    }

    override suspend fun resendCode(
        phoneNumber: String,
        phoneCodeHash: String,
        reason: String?,
    ): SentCode_250764ccd9 {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank" }
        require(phoneCodeHash.isNotBlank()) { "phoneCodeHash must not be blank" }
        return transport.execute(ResendCode(phoneNumber, phoneCodeHash, reason))
    }

    override suspend fun signIn(
        phoneNumber: String,
        phoneCodeHash: String,
        phoneCode: String,
    ): Authorization_fb75ff221f {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank" }
        require(phoneCodeHash.isNotBlank()) { "phoneCodeHash must not be blank" }
        require(phoneCode.isNotBlank()) { "phoneCode must not be blank" }
        return transport.execute(SignIn(phoneNumber, phoneCodeHash, phoneCode, emailVerification = null))
    }

    override suspend fun signInWithEmailCode(
        phoneNumber: String,
        phoneCodeHash: String,
        emailCode: String,
    ): Authorization_fb75ff221f {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank" }
        require(phoneCodeHash.isNotBlank()) { "phoneCodeHash must not be blank" }
        require(emailCode.isNotBlank()) { "emailCode must not be blank" }
        return transport.execute(SignIn(phoneNumber, phoneCodeHash, null, EmailVerificationCode(emailCode)))
    }

    override suspend fun signUp(
        phoneNumber: String,
        phoneCodeHash: String,
        firstName: String,
        lastName: String,
    ): Authorization_fb75ff221f {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank" }
        require(phoneCodeHash.isNotBlank()) { "phoneCodeHash must not be blank" }
        require(firstName.isNotBlank()) { "firstName must not be blank" }
        return transport.execute(SignUp(false, phoneNumber, phoneCodeHash, firstName, lastName))
    }

    override suspend fun getPasswordChallengeInfo(): MtProtoPasswordChallengeInfo {
        val password = getPasswordConfiguration()
        return MtProtoPasswordChallengeInfo(
            hint = password.hint?.takeIf(String::isNotBlank),
            hasRecoveryEmail = password.hasRecovery,
        )
    }

    override suspend fun checkPassword(password: String): Authorization_fb75ff221f {
        require(password.isNotBlank()) { "password must not be blank" }
        var retryFreshChallenge = true
        while (true) {
            try {
                return checkPasswordOnce(password)
            } catch (rpc: MtProtoRpcException) {
                if (!retryFreshChallenge || rpc.errorCode != 400 || rpc.rpcMessage != SRP_ID_INVALID) throw rpc
                retryFreshChallenge = false
            }
        }
    }

    private suspend fun checkPasswordOnce(password: String): Authorization_fb75ff221f {
        val configuration = getPasswordConfiguration()
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
            transport.execute(
                CheckPassword(
                    InputCheckPasswordSrp_5100d694df(
                        srpId = proof.srpId,
                        a = TlBytes.copyOf(proof.a),
                        m1 = TlBytes.copyOf(proof.m1),
                    ),
                ),
            )
        } finally {
            proof.a.fill(0)
            proof.m1.fill(0)
        }
    }

    private suspend fun getPasswordConfiguration(): Password_ac67a26d5c {
        val configuration = transport.execute(GetPassword) as? Password_ac67a26d5c
            ?: error("Unsupported MTProto password configuration")
        check(configuration.hasPassword) { "MTProto account has no password challenge" }
        check(configuration.currentAlgo is PasswordKdfAlgoSha256Sha256Pbkdf2Hmacsha512Iter100000Sha256ModPow) {
            "Unsupported MTProto password KDF"
        }
        check(configuration.srpB != null) { "MTProto password challenge is missing srpB" }
        check(configuration.srpId != null) { "MTProto password challenge is missing srpId" }
        return configuration
    }

    private companion object {
        const val SRP_ID_INVALID = "SRP_ID_INVALID"
    }
}
