package org.monogram.mtproto.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.mtproto.crypto.EntropySource
import org.monogram.mtproto.crypto.SecureEntropySource
import org.monogram.mtproto.crypto.TelegramPasswordSrp
import org.monogram.mtproto.tl.generated.cloud.layer223.EmailVerificationCode
import org.monogram.mtproto.tl.generated.cloud.layer223.EmailVerifyPurposeLoginSetup
import org.monogram.mtproto.tl.generated.cloud.layer223.InputCheckPasswordSrp_5100d694df
import org.monogram.mtproto.tl.generated.cloud.layer223.PasswordKdfAlgoSha256Sha256Pbkdf2Hmacsha512Iter100000Sha256ModPow
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_fb610807ca
import org.monogram.mtproto.tl.generated.cloud.layer223.account.EmailVerifiedLogin
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetPassword
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SendVerifyEmailCode
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SentEmailCode_c0a17c9b71
import org.monogram.mtproto.tl.generated.cloud.layer223.account.VerifyEmail
import org.monogram.mtproto.tl.generated.cloud.layer223.account.Password_ac67a26d5c
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_fb75ff221f
import org.monogram.mtproto.tl.generated.cloud.layer223.DataJson_340cf194d4
import org.monogram.mtproto.tl.generated.cloud.layer223.help.AcceptTermsOfService
import org.monogram.mtproto.tl.generated.cloud.layer223.help.GetTermsOfServiceUpdate
import org.monogram.mtproto.tl.generated.cloud.layer223.help.TermsOfServiceUpdateEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.help.TermsOfServiceUpdate_db081ee702
import org.monogram.mtproto.tl.generated.cloud.layer223.help.TermsOfService_ca69dd05f0
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.CheckPassword
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ImportBotAuthorization
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.CheckRecoveryPassword
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.PasswordRecovery_6a609d1aeb
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.RecoverPassword
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.RequestPasswordRecovery
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ResendCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SendCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCode_250764ccd9
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SignIn
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SignUp
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.transport.MtProtoRpcException
import org.monogram.mtproto.transport.MtProtoRpcTransport

data class MtProtoLoginSetupEmailCode(
    val emailPattern: String,
    val length: Int,
)

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

    /** Requests the recovery email pattern for the current 2FA challenge. */
    suspend fun requestPasswordRecovery(): String

    /** Verifies a recovery code without consuming it; false when the code is invalid. */
    suspend fun checkRecoveryPassword(code: String): Boolean

    /** Completes login with a recovery code, invalidating the 2FA password until reset. */
    suspend fun recoverPassword(code: String): Authorization_fb75ff221f

    suspend fun termsOfServiceUpdate(): MtProtoTermsOfServiceState

    suspend fun acceptTermsOfService(idData: String): Boolean

    /** Completes login for a bot integration using its token from BotFather. */
    suspend fun importBotAuthorization(apiId: Int, apiHash: String, botAuthToken: String): Authorization_fb75ff221f

    suspend fun signInWithEmailCode(
        phoneNumber: String,
        phoneCodeHash: String,
        emailCode: String,
    ): Authorization_fb75ff221f

    suspend fun sendLoginSetupEmail(
        phoneNumber: String,
        phoneCodeHash: String,
        email: String,
    ): MtProtoLoginSetupEmailCode

    suspend fun verifyLoginSetupEmail(
        phoneNumber: String,
        phoneCodeHash: String,
        code: String,
    ): SentCode_250764ccd9

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

    override suspend fun sendLoginSetupEmail(
        phoneNumber: String,
        phoneCodeHash: String,
        email: String,
    ): MtProtoLoginSetupEmailCode {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank" }
        require(phoneCodeHash.isNotBlank()) { "phoneCodeHash must not be blank" }
        require(email.isNotBlank()) { "email must not be blank" }
        val result = transport.execute(
            SendVerifyEmailCode(EmailVerifyPurposeLoginSetup(phoneNumber, phoneCodeHash), email)
        ) as? SentEmailCode_c0a17c9b71 ?: error("Unsupported login setup email result")
        return MtProtoLoginSetupEmailCode(result.emailPattern, result.length)
    }

    override suspend fun verifyLoginSetupEmail(
        phoneNumber: String,
        phoneCodeHash: String,
        code: String,
    ): SentCode_250764ccd9 {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank" }
        require(phoneCodeHash.isNotBlank()) { "phoneCodeHash must not be blank" }
        require(code.isNotBlank()) { "code must not be blank" }
        val result = transport.execute(
            VerifyEmail(
                EmailVerifyPurposeLoginSetup(phoneNumber, phoneCodeHash),
                EmailVerificationCode(code),
            )
        ) as? EmailVerifiedLogin ?: error("Login setup email was not verified")
        return result.sentCode
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


    /** Returns the pending terms-of-service update; `Current` means nothing requires acceptance. */
    override suspend fun termsOfServiceUpdate(): MtProtoTermsOfServiceState {
        return when (val update = transport.execute(GetTermsOfServiceUpdate)) {
            is TermsOfServiceUpdateEmpty -> MtProtoTermsOfServiceState.Current
            is TermsOfServiceUpdate_db081ee702 -> {
                val terms = update.termsOfService as? TermsOfService_ca69dd05f0
                    ?: error("Unsupported help.termsOfService constructor ${update.termsOfService.constructorId}")
                val id = terms.id as? DataJson_340cf194d4
                    ?: error("Unsupported terms identifier ${terms.id.constructorId}")
                MtProtoTermsOfServiceState.Pending(
                    expiresAtSeconds = update.expires,
                    popup = terms.popup,
                    idData = id.data_,
                    text = terms.text,
                    minAgeConfirmYears = terms.minAgeConfirm,
                )
            }
            else -> error("Unsupported help.getTermsOfServiceUpdate constructor ${update.constructorId}")
        }
    }

    /** Accepts a pending terms-of-service update by its JSON identifier. */
    override suspend fun acceptTermsOfService(idData: String): Boolean {
        require(idData.isNotBlank()) { "terms identifier must not be blank" }
        return transport.execute(AcceptTermsOfService(DataJson_340cf194d4(idData)))
    }

    override suspend fun importBotAuthorization(
        apiId: Int,
        apiHash: String,
        botAuthToken: String,
    ): Authorization_fb75ff221f {
        require(apiId > 0) { "apiId must be positive" }
        require(apiHash.isNotBlank()) { "apiHash must not be blank" }
        require(botAuthToken.isNotBlank()) { "bot auth token must not be blank" }
        return transport.execute(ImportBotAuthorization(flags = 0, apiId = apiId, apiHash = apiHash, botAuthToken = botAuthToken.trim()))
    }

    override suspend fun requestPasswordRecovery(): String {
        val recovery = transport.execute(RequestPasswordRecovery)
        return (recovery as? PasswordRecovery_6a609d1aeb)?.emailPattern
            ?: error("auth.requestPasswordRecovery returned an unsupported payload")
    }

    override suspend fun checkRecoveryPassword(code: String): Boolean {
        require(code.isNotBlank()) { "recovery code must not be blank" }
        return transport.execute(CheckRecoveryPassword(code.trim()))
    }

    override suspend fun recoverPassword(code: String): Authorization_fb75ff221f {
        require(code.isNotBlank()) { "recovery code must not be blank" }
        return transport.execute(RecoverPassword(code.trim(), newSettings = null))
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

    private suspend fun checkPasswordOnce(password: String): Authorization_fb75ff221f =
        transport.execute(CheckPassword(MtProtoPasswordSrpProof.create(password, getPasswordConfiguration(), entropy)))

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
