package org.monogram.data.gateway

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.TdApi

interface TelegramGateway {
    suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T
    val updates: SharedFlow<TdApi.Update>
    val isAuthenticated: StateFlow<Boolean>
}
