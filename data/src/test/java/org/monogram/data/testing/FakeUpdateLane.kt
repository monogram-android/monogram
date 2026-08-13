package org.monogram.data.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.CoroutineContext

/**
 * Lane implementation for test doubles of `TelegramGateway` / `UpdateDispatcher`.
 *
 * Backs the lane with a plain collector on the fake's update flow. That is enough for
 * tests, which control emission rate and never overflow anything; the production
 * implementation in `TdUpdatePipeline` is what provides the losslessness and ordering
 * guarantees, and it is covered by `TdUpdatePipelineTest`.
 */
internal fun fakeUpdateLane(
    source: Flow<TdApi.Update>,
    scope: CoroutineScope,
    context: CoroutineContext,
    filter: (TdApi.Update) -> Boolean,
    handler: suspend (TdApi.Update) -> Unit,
) {
    scope.launch(context) {
        source.filter(filter).collect { handler(it) }
    }
}
