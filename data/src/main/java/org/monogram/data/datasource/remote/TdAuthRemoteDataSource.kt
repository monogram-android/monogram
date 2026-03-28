package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway

class TdAuthRemoteDataSource(
    private val gateway: TelegramGateway
) : AuthRemoteDataSource {

    override suspend fun setTdlibParameters(parameters: TdApi.SetTdlibParameters) {
        gateway.execute(parameters)
    }

    override suspend fun setPhoneNumber(phone: String) {
        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            isCurrentPhoneNumber = false
            allowFlashCall = false
            allowMissedCall = false
            allowSmsRetrieverApi = false
        }
        gateway.execute(TdApi.SetAuthenticationPhoneNumber(phone, settings))
    }

    override suspend fun resendCode() {
        gateway.execute(TdApi.ResendAuthenticationCode())
    }

    override suspend fun setAuthCode(code: String) {
        gateway.execute(TdApi.CheckAuthenticationCode(code))
    }

    override suspend fun checkEmailCode(code: String) {
        gateway.execute(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code)))
    }

    override suspend fun checkPassword(password: String) {
        gateway.execute(TdApi.CheckAuthenticationPassword(password))
    }

    override suspend fun logout() {
        gateway.execute(TdApi.LogOut())
    }
}