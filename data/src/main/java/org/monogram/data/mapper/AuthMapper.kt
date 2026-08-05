package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.repository.AuthCodeDelivery
import org.monogram.domain.repository.AuthCodeInputKind
import org.monogram.domain.repository.AuthStep

fun TdApi.AuthorizationState.toDomain(): AuthStep =
    when (this) {
        is TdApi.AuthorizationStateReady ->
            AuthStep.Ready

        is TdApi.AuthorizationStateWaitPhoneNumber ->
            AuthStep.InputPhone

        is TdApi.AuthorizationStateWaitCode -> {
            val codeMetadata = this.codeInfo.type.toAuthCodeMetadata()
            AuthStep.InputCode(
                delivery = codeMetadata.delivery,
                codeLength = codeMetadata.codeLength,
                inputKind = codeMetadata.inputKind,
                codeHint = codeMetadata.hint,
                nextDelivery = this.codeInfo.nextType?.toAuthCodeMetadata()?.delivery,
                timeout = this.codeInfo.timeout,
                canResend = this.codeInfo.nextType != null
            )
        }

        is TdApi.AuthorizationStateWaitEmailCode ->
            AuthStep.InputCode(
                delivery = AuthCodeDelivery.EMAIL,
                codeLength = this.codeInfo.length,
                isEmailCode = true,
                emailPattern = this.codeInfo.emailAddressPattern,
                canResend = true
            )

        is TdApi.AuthorizationStateWaitPassword ->
            AuthStep.InputPassword(
                passwordHint = this.passwordHint.takeIf { it.isNotBlank() },
                hasRecoveryEmail = this.hasRecoveryEmailAddress,
                recoveryEmailPattern = this.recoveryEmailAddressPattern.takeIf { it.isNotBlank() }
            )

        is TdApi.AuthorizationStateWaitTdlibParameters ->
            AuthStep.WaitParameters

        is TdApi.AuthorizationStateLoggingOut,
        is TdApi.AuthorizationStateClosing,
        is TdApi.AuthorizationStateClosed ->
            AuthStep.Closing

        else ->
            AuthStep.Loading
    }

private data class AuthCodeMetadata(
    val delivery: AuthCodeDelivery,
    val codeLength: Int,
    val inputKind: AuthCodeInputKind = AuthCodeInputKind.NUMERIC,
    val hint: String? = null
)

private fun TdApi.AuthenticationCodeType.toAuthCodeMetadata(): AuthCodeMetadata =
    when (this) {
        is TdApi.AuthenticationCodeTypeTelegramMessage ->
            AuthCodeMetadata(AuthCodeDelivery.TELEGRAM_MESSAGE, length)

        is TdApi.AuthenticationCodeTypeSms ->
            AuthCodeMetadata(AuthCodeDelivery.SMS, length)

        is TdApi.AuthenticationCodeTypeSmsWord ->
            AuthCodeMetadata(AuthCodeDelivery.SMS_WORD, 0, AuthCodeInputKind.TEXT, firstLetter)

        is TdApi.AuthenticationCodeTypeSmsPhrase ->
            AuthCodeMetadata(AuthCodeDelivery.SMS_PHRASE, 0, AuthCodeInputKind.TEXT, firstWord)

        is TdApi.AuthenticationCodeTypeCall ->
            AuthCodeMetadata(AuthCodeDelivery.CALL, length)

        is TdApi.AuthenticationCodeTypeFlashCall ->
            AuthCodeMetadata(AuthCodeDelivery.FLASH_CALL, 0, hint = pattern)

        is TdApi.AuthenticationCodeTypeMissedCall ->
            AuthCodeMetadata(AuthCodeDelivery.MISSED_CALL, length, hint = phoneNumberPrefix)

        is TdApi.AuthenticationCodeTypeFragment ->
            AuthCodeMetadata(AuthCodeDelivery.FRAGMENT, length)

        is TdApi.AuthenticationCodeTypeFirebaseAndroid ->
            AuthCodeMetadata(AuthCodeDelivery.FIREBASE_ANDROID, length)

        is TdApi.AuthenticationCodeTypeFirebaseIos ->
            AuthCodeMetadata(AuthCodeDelivery.FIREBASE_IOS, length)

        else ->
            AuthCodeMetadata(AuthCodeDelivery.UNKNOWN, 0)
    }
