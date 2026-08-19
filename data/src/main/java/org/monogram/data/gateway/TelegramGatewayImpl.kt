package org.monogram.data.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.TdApi
import org.monogram.data.di.TdLibClient
import kotlin.coroutines.CoroutineContext

internal class TelegramGatewayImpl(
    clientProvider: () -> TdLibClient,
) : TelegramGateway {
    private val client by lazy(clientProvider)

    override suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T =
        client.sendSuspend(function)

    override val updates: SharedFlow<TdApi.Update>
        get() = client.updates

    override val isAuthenticated: StateFlow<Boolean>
        get() = client.isAuthenticated

    override fun lane(
        name: String,
        scope: CoroutineScope,
        context: CoroutineContext,
        filter: (TdApi.Update) -> Boolean,
        handler: suspend (TdApi.Update) -> Unit,
    ) {
        client.lane(name, scope, context, filter, handler)
    }

    /** Diagnostics: ingest/lane backlogs, processed counts and handler failures. */
    fun updateMetrics(): String = client.updateMetrics()
}
