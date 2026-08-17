package org.monogram.data.mtproto

import org.monogram.domain.repository.AuthCodeDelivery
import org.monogram.domain.repository.AuthCodeInputKind
import org.monogram.domain.repository.AuthStep
import org.monogram.mtproto.auth.MtProtoAuthorizationApi
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

internal class MtProtoPhoneAuthSession(
    private val api: MtProtoAuthorizationApi,
    private val apiId: Int,
    private val apiHash: String,
    private val codeSettings: CodeSettings_fb610807ca,
) {
    private var phoneNumber: String? = null
    private var phoneCodeHash: String? = null
    private var state: AuthStep = AuthStep.InputPhone

    fun currentState(): AuthStep = state

    suspend fun requestCode(phone: String): AuthStep {
        val sent = api.sendCode(phone, codeSettings, apiId, apiHash)
        phoneNumber = phone
        return applySentCode(sent)
    }

    suspend fun resendCode(): AuthStep {
        val phone = phoneNumber ?: error("Phone authorization has not started")
        val hash = phoneCodeHash ?: error("No phone code is available to resend")
        return applySentCode(api.resendCode(phone, hash))
    }

    suspend fun submitCode(code: String): AuthStep {
        val phone = phoneNumber ?: error("Phone authorization has not started")
        val hash = phoneCodeHash ?: error("No phone code is available")
        require(code.isNotBlank()) { "code must not be blank" }
        return when (val authorization = api.signIn(phone, hash, code)) {
            is org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3 -> {
                state = AuthStep.Ready
                state
            }
            is AuthorizationSignUpRequired -> throw UnsupportedOperationException("MTProto signup is not implemented")
            else -> throw IllegalStateException("Unsupported MTProto authorization result: ${authorization.constructorId}")
        }
    }

    private fun applySentCode(sent: org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCode_250764ccd9): AuthStep =
        when (sent) {
            is SentCodeSuccess -> when (sent.authorization) {
                is org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3 -> {
                    phoneCodeHash = null
                    state = AuthStep.Ready
                    state
                }
                is AuthorizationSignUpRequired -> throw UnsupportedOperationException("MTProto signup is not implemented")
                else -> throw IllegalStateException("Unsupported MTProto authorization result")
            }
            is SentCodePaymentRequired -> throw UnsupportedOperationException("MTProto paid-code flow is not implemented")
            is SentCode_f9e8fc1d16 -> {
                phoneCodeHash = sent.phoneCodeHash
                val details = sent.type.toDomainDetails()
                state = AuthStep.InputCode(
                    delivery = details.delivery,
                    codeLength = details.length,
                    inputKind = AuthCodeInputKind.NUMERIC,
                    codeHint = details.hint,
                    timeout = sent.timeout ?: 0,
                    isEmailCode = details.emailPattern != null,
                    emailPattern = details.emailPattern,
                    canResend = true,
                )
                state
            }
            else -> throw IllegalStateException("Unsupported MTProto sent-code result")
        }

    private data class CodeDetails(
        val delivery: AuthCodeDelivery,
        val length: Int,
        val hint: String? = null,
        val emailPattern: String? = null,
    )

    private fun SentCodeType.toDomainDetails(): CodeDetails = when (this) {
        is SentCodeTypeApp -> CodeDetails(AuthCodeDelivery.TELEGRAM_MESSAGE, length)
        is SentCodeTypeSms -> CodeDetails(AuthCodeDelivery.SMS, length)
        is SentCodeTypeSmsWord -> CodeDetails(AuthCodeDelivery.SMS_WORD, 0, beginning)
        is SentCodeTypeSmsPhrase -> CodeDetails(AuthCodeDelivery.SMS_PHRASE, 0, beginning)
        is SentCodeTypeCall -> CodeDetails(AuthCodeDelivery.CALL, length)
        is SentCodeTypeFlashCall -> CodeDetails(AuthCodeDelivery.FLASH_CALL, 0, pattern)
        is SentCodeTypeMissedCall -> CodeDetails(AuthCodeDelivery.MISSED_CALL, length, prefix)
        is SentCodeTypeFragmentSms -> CodeDetails(AuthCodeDelivery.FRAGMENT, length, url)
        is SentCodeTypeFirebaseSms -> CodeDetails(AuthCodeDelivery.FIREBASE_ANDROID, length)
        is SentCodeTypeEmailCode -> CodeDetails(AuthCodeDelivery.EMAIL, length, emailPattern = emailPattern)
        is SentCodeTypeSetUpEmailRequired -> throw UnsupportedOperationException("MTProto email setup is not implemented")
        else -> throw IllegalStateException("Unsupported MTProto sent-code type: ${constructorId}")
    }
}
