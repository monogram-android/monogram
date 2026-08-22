package org.monogram.data.push

import kotlinx.coroutines.CoroutineScope

/**
 * Bridges push wake-ups to the MTProto stack.
 *
 * A push carries no message content; it means "the server has something new." The executor
 * forces a server round trip so the socket refreshes and the live coordinator's inbox receives
 * pending updates. Requests are conflated with a minimum interval so bursty deliveries don't
 * hammer the network. Sync failures are non-fatal: the live coordinator retries independently.
 */
internal class MtProtoPushSync(
    scope: CoroutineScope,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val execute: suspend (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val queue = ConflatedSyncRequestQueue(scope, minIntervalMs, execute, nowMs)

    /** Enqueues a sync request; returns false when the queue rejected it (saturated). */
    fun requestSync(reason: String): Boolean = queue.request(reason)

    private companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 3_000L
    }
}
