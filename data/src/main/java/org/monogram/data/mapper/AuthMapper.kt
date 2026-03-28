package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.repository.AuthStep

fun TdApi.AuthorizationState.toDomain(): AuthStep =
    when (this) {
        is TdApi.AuthorizationStateReady ->
            AuthStep.Ready

        is TdApi.AuthorizationStateWaitPhoneNumber ->
            AuthStep.InputPhone

        is TdApi.AuthorizationStateWaitCode ->
            AuthStep.InputCode(
                codeType = this.codeInfo.type.javaClass.simpleName,
                codeLength = this.codeInfo.type.let { type ->
                    when (type) {
                        is TdApi.AuthenticationCodeTypeTelegramMessage -> type.length
                        is TdApi.AuthenticationCodeTypeSms -> type.length
                        is TdApi.AuthenticationCodeTypeCall -> type.length
                        is TdApi.AuthenticationCodeTypeFlashCall -> 0
                        is TdApi.AuthenticationCodeTypeMissedCall -> type.length
                        else -> 5
                    }
                },
                nextType = this.codeInfo.nextType?.javaClass?.simpleName,
                timeout = this.codeInfo.timeout
            )

        is TdApi.AuthorizationStateWaitEmailCode ->
            AuthStep.InputCode(
                codeType = "Email",
                codeLength = this.codeInfo.length,
                isEmailCode = true,
                emailPattern = this.codeInfo.emailAddressPattern
            )

        is TdApi.AuthorizationStateWaitPassword ->
            AuthStep.InputPassword

        is TdApi.AuthorizationStateWaitTdlibParameters ->
            AuthStep.WaitParameters

        else ->
            AuthStep.Loading
    }