package org.monogram.data.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

interface TelegramGateway {
    suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T

    /**
     * Observation stream. Conflates when a collector falls behind; use [lane] for
     * anything that drives durable state.
     */
    val updates: SharedFlow<TdApi.Update>

    val isAuthenticated: StateFlow<Boolean>

    /**
     * Lossless, strictly ordered, exception-isolated update subscription.
     * See [UpdateDispatcher.lane].
     */
    fun lane(
        name: String,
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext,
        filter: (TdApi.Update) -> Boolean = { true },
        handler: suspend (TdApi.Update) -> Unit,
    )
}
