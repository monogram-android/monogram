package org.monogram.data.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ConflatedSyncRequestQueue(
    scope: CoroutineScope,
    private val minIntervalMs: Long,
    private val execute: suspend (String) -> Unit
) {
    private val requests = Channel<String>(Channel.CONFLATED)

    init {
        scope.launch {
            var lastCompletedAt = 0L
            for (reason in requests) {
                val waitMs = (lastCompletedAt + minIntervalMs - System.currentTimeMillis())
                    .coerceAtLeast(0L)
                if (waitMs > 0L) {
                    delay(waitMs)
                }

                execute(reason)
                lastCompletedAt = System.currentTimeMillis()
            }
        }
    }

    fun request(reason: String): Boolean = requests.trySend(reason).isSuccess
}
