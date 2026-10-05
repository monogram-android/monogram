package org.monogram.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes proxy probes. The process has one MTProto proxy, so checks must not overlap,
 * but callers can enqueue several and observe [checking] immediately.
 */
class ProxyCheckQueue(
    private val scope: CoroutineScope,
    private val check: suspend (String) -> Long?,
    private val restore: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val checkingKeys = MutableStateFlow<Set<String>>(emptySet())
    val checking: StateFlow<Set<String>> = checkingKeys.asStateFlow()

    /** Returns false when this key is already queued or running. */
    fun request(key: String, onResult: (Long?) -> Unit): Boolean {
        while (true) {
            val current = checkingKeys.value
            if (key in current) return false
            if (checkingKeys.compareAndSet(current, current + key)) break
        }
        scope.launch {
            var latency: Long? = null
            var failed = false
            try {
                try {
                    latency = mutex.withLock {
                        try {
                            check(key)
                        } finally {
                            restore()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    failed = true
                }
                onResult(if (failed) null else latency)
            } finally {
                checkingKeys.update { it - key }
            }
        }
        return true
    }

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
