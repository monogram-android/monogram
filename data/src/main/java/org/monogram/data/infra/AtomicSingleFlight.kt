package org.monogram.data.infra

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

internal class AtomicSingleFlight<K, V>(
    private val scope: CoroutineScope
) {
    private val requests = ConcurrentHashMap<K, Deferred<V>>()
    private val requestScope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])
    )

    suspend fun execute(key: K, block: suspend () -> V): V {
        val candidate = requestScope.async(start = CoroutineStart.LAZY) { block() }
        val selected = requests.putIfAbsent(key, candidate) ?: candidate
        if (selected === candidate) {
            candidate.invokeOnCompletion {
                requests.remove(key, candidate)
            }
            candidate.start()
        } else {
            candidate.cancel()
        }
        return selected.await()
    }
}
