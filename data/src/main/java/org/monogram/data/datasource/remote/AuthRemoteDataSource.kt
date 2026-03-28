package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi

interface AuthRemoteDataSource {
    suspend fun setTdlibParameters(parameters: TdApi.SetTdlibParameters)
    suspend fun setPhoneNumber(phone: String)
    suspend fun resendCode()
    suspend fun setAuthCode(code: String)
    suspend fun checkEmailCode(code: String)
    suspend fun checkPassword(password: String)
    suspend fun logout()
}