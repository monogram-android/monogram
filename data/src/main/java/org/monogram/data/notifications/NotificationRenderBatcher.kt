package org.monogram.data.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Coalesces related TDLib notification updates before the Android render pass. */
internal class NotificationRenderBatcher(
    scope: CoroutineScope,
    private val batchWindowMs: Long = DEFAULT_BATCH_WINDOW_MS,
    private val render: suspend (Set<Long>) -> Unit
) {
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val pendingChatIds = linkedSetOf<Long>()

    init {
        scope.launch {
            for (ignored in trigger) {
                delay(batchWindowMs)
                val batch = synchronized(pendingChatIds) {
                    pendingChatIds.toSet().also { pendingChatIds.clear() }
                }
                if (batch.isNotEmpty()) render(batch)
            }
        }
    }

    fun enqueue(chatIds: Set<Long>) {
        if (chatIds.isEmpty()) return
        synchronized(pendingChatIds) {
            pendingChatIds += chatIds
        }
        trigger.trySend(Unit)
    }

    private companion object {
        const val DEFAULT_BATCH_WINDOW_MS = 16L
    }
}
