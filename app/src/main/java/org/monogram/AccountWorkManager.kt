package org.monogram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AccountBackgroundWork {
    fun isRunning(): Boolean
    fun start(scope: CoroutineScope)
    fun resume()
    suspend fun stop()
}

class AccountWorkManager<R>(
    private val scope: CoroutineScope,
    private val accountId: (R) -> Long,
    private val prepare: suspend (R) -> Unit = {},
    private val create: (R) -> AccountBackgroundWork,
) {
    private val mutex = Mutex()
    private val work = LinkedHashMap<Long, AccountBackgroundWork>()

    suspend fun start(runtime: R) = mutex.withLock {
        val userId = accountId(runtime)
        val worker = work[userId] ?: create(runtime).also { work[userId] = it }
        if (!worker.isRunning()) {
            prepare(runtime)
            worker.start(scope)
        }
    }

    suspend fun stop(userId: Long) = mutex.withLock {
        work.remove(userId)?.stop()
    }

    suspend fun resume(userId: Long) = mutex.withLock {
        work[userId]?.resume()
    }

    suspend fun runningAccountIds(): Set<Long> = mutex.withLock { work.keys.toSet() }
}
