package org.monogram.data.mtproto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.domain.repository.AuthCodeDelivery
import org.monogram.domain.repository.AuthCodeInputKind
import org.monogram.domain.repository.AuthStep
import org.monogram.mtproto.auth.MtProtoAuthorizationApi
import org.monogram.mtproto.auth.MtProtoPasswordChallengeInfo
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.CodeType
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.CodeTypeCall
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.CodeTypeFlashCall
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.CodeTypeFragmentSms
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.CodeTypeMissedCall
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.CodeTypeSms
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_fb610807ca
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.AuthorizationSignUpRequired
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodePaymentRequired
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeSuccess
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeType
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeApp
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeCall
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeEmailCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeFirebaseSms
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeFlashCall
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeFragmentSms
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeMissedCall
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeSetUpEmailRequired
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeSms
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeSmsPhrase
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeSmsWord
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCode_f9e8fc1d16
import org.monogram.mtproto.transport.MtProtoRpcException

internal class MtProtoPhoneAuthSession(
    private val api: MtProtoAuthorizationApi,
    private val apiId: Int,
    private val apiHash: String,
    private val codeSettings: CodeSettings_fb610807ca,
) {
    private val mutex = Mutex()
    private var phoneNumber: String? = null
    private var phoneCodeHash: String? = null
    @Volatile
    private var state: AuthStep = AuthStep.InputPhone

    fun currentState(): AuthStep = state

    suspend fun requestCode(phone: String): AuthStep = mutex.withLock {
        val sent = api.sendCode(phone, codeSettings, apiId, apiHash)
        val outcome = mapSentCode(sent)
        commitOutcome(phone, outcome)
        state
    }

    suspend fun resendCode(): AuthStep = mutex.withLock {
        val phone = phoneNumber ?: error("Phone authorization has not started")
        val hash = phoneCodeHash ?: error("No phone code is available to resend")
        check((state as? AuthStep.InputCode)?.canResend == true) { "Phone code cannot be resent" }
        val outcome = mapSentCode(api.resendCode(phone, hash))
        commitOutcome(phone, outcome)
        state
    }

    suspend fun submitCode(code: String): AuthStep = mutex.withLock {
        val phone = phoneNumber ?: error("Phone authorization has not started")
        val hash = phoneCodeHash ?: error("No phone code is available")
        check(state is AuthStep.InputCode) { "Phone code is not expected" }
        require(code.isNotBlank()) { "code must not be blank" }
        return try {
            when (val authorization = api.signIn(phone, hash, code)) {
                is org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3 -> {
                    markReady()
                    state
                }
                is AuthorizationSignUpRequired -> throw UnsupportedOperationException("MTProto signup is not implemented")
                else -> throw IllegalStateException("Unsupported MTProto authorization result: ${authorization.constructorId}")
            }
        } catch (rpc: MtProtoRpcException) {
            if (rpc.errorCode != 400 || rpc.rpcMessage != SESSION_PASSWORD_NEEDED) throw rpc
            val challenge = api.getPasswordChallengeInfo()
            phoneNumber = null
            phoneCodeHash = null
            state = AuthStep.InputPassword(
                passwordHint = challenge.hint,
                hasRecoveryEmail = challenge.hasRecoveryEmail,
            )
            state
        }
    }

    suspend fun submitPassword(password: String): AuthStep = mutex.withLock {
        check(state is AuthStep.InputPassword) { "Password is not expected" }
        require(password.isNotBlank()) { "password must not be blank" }
        when (val authorization = api.checkPassword(password)) {
            is org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3 -> {
                markReady()
                state
            }
            is AuthorizationSignUpRequired -> throw UnsupportedOperationException("MTProto signup is not implemented")
            else -> throw IllegalStateException("Unsupported MTProto authorization result: ${authorization.constructorId}")
        }
    }

    private data class SentCodeOutcome(
        val state: AuthStep,
        val phoneCodeHash: String?,
    )

    private fun mapSentCode(
        sent: org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCode_250764ccd9,
    ): SentCodeOutcome =
        when (sent) {
            is SentCodeSuccess -> when (sent.authorization) {
                is org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3 -> {
                    SentCodeOutcome(AuthStep.Ready, null)
                }
                is AuthorizationSignUpRequired -> throw UnsupportedOperationException("MTProto signup is not implemented")
                else -> throw IllegalStateException("Unsupported MTProto authorization result")
            }
            is SentCodePaymentRequired -> throw UnsupportedOperationException("MTProto paid-code flow is not implemented")
            is SentCode_f9e8fc1d16 -> {
                val details = sent.type.toDomainDetails()
                SentCodeOutcome(
                    state = AuthStep.InputCode(
                        delivery = details.delivery,
                        codeLength = details.length,
                        inputKind = details.inputKind,
                        codeHint = details.hint,
                        nextDelivery = sent.nextType?.toDomainDelivery(),
                        timeout = sent.timeout ?: 0,
                        isEmailCode = details.emailPattern != null,
                        emailPattern = details.emailPattern,
                        canResend = sent.nextType != null,
                    ),
                    phoneCodeHash = sent.phoneCodeHash,
                )
            }
            else -> throw IllegalStateException("Unsupported MTProto sent-code result")
        }

    private data class CodeDetails(
        val delivery: AuthCodeDelivery,
        val length: Int,
        val inputKind: AuthCodeInputKind = AuthCodeInputKind.NUMERIC,
        val hint: String? = null,
        val emailPattern: String? = null,
    )

    private fun SentCodeType.toDomainDetails(): CodeDetails = when (this) {
        is SentCodeTypeApp -> CodeDetails(AuthCodeDelivery.TELEGRAM_MESSAGE, length)
        is SentCodeTypeSms -> CodeDetails(AuthCodeDelivery.SMS, length)
        is SentCodeTypeSmsWord -> CodeDetails(AuthCodeDelivery.SMS_WORD, 0, AuthCodeInputKind.TEXT, beginning)
        is SentCodeTypeSmsPhrase -> CodeDetails(AuthCodeDelivery.SMS_PHRASE, 0, AuthCodeInputKind.TEXT, beginning)
        is SentCodeTypeCall -> CodeDetails(AuthCodeDelivery.CALL, length)
        is SentCodeTypeFlashCall -> CodeDetails(AuthCodeDelivery.FLASH_CALL, 0, hint = pattern)
        is SentCodeTypeMissedCall -> CodeDetails(AuthCodeDelivery.MISSED_CALL, length, hint = prefix)
        is SentCodeTypeFragmentSms -> CodeDetails(AuthCodeDelivery.FRAGMENT, length, hint = url)
        is SentCodeTypeFirebaseSms -> CodeDetails(AuthCodeDelivery.FIREBASE_ANDROID, length)
        is SentCodeTypeEmailCode -> throw UnsupportedOperationException("MTProto email-code sign-in is not implemented")
        is SentCodeTypeSetUpEmailRequired -> throw UnsupportedOperationException("MTProto email setup is not implemented")
        else -> throw IllegalStateException("Unsupported MTProto sent-code type: ${constructorId}")
    }

    private fun CodeType.toDomainDelivery(): AuthCodeDelivery = when (this) {
        CodeTypeSms -> AuthCodeDelivery.SMS
        CodeTypeCall -> AuthCodeDelivery.CALL
        CodeTypeFlashCall -> AuthCodeDelivery.FLASH_CALL
        CodeTypeMissedCall -> AuthCodeDelivery.MISSED_CALL
        CodeTypeFragmentSms -> AuthCodeDelivery.FRAGMENT
        else -> AuthCodeDelivery.UNKNOWN
    }

    private fun commitOutcome(phone: String, outcome: SentCodeOutcome) {
        if (outcome.state is AuthStep.Ready) {
            markReady()
            return
        }
        phoneNumber = phone
        phoneCodeHash = outcome.phoneCodeHash
        state = outcome.state
    }

    private fun markReady() {
        phoneNumber = null
        phoneCodeHash = null
        state = AuthStep.Ready
    }

    private companion object {
        const val SESSION_PASSWORD_NEEDED = "SESSION_PASSWORD_NEEDED"
    }
}
