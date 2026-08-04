package org.monogram.data.infra

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class LatestByKeyBatcher<K, V>(
    scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onBatch: suspend (Map<K, V>) -> Unit
) {
    private val latest = ConcurrentHashMap<K, V>()
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in signals) {
                if (debounceMs > 0L) delay(debounceMs)
                val snapshot = latest.toMap()
                snapshot.forEach { (key, value) -> latest.remove(key, value) }
                if (snapshot.isNotEmpty()) {
                    runCatching { onBatch(snapshot) }
                        .onFailure { error ->
                            Log.e(
                                TAG,
                                "Failed to apply latest keyed batch",
                                error
                            )
                        }
                }
            }
        }
    }

    fun offer(key: K, value: V): Boolean {
        latest[key] = value
        return signals.trySend(Unit).isSuccess
    }

    private companion object {
        const val TAG = "LatestByKeyBatcher"
        const val DEFAULT_DEBOUNCE_MS = 8L
    }
}
