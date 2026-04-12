package org.monogram.data.push

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.monogram.data.infra.ConnectionManager
import java.util.concurrent.atomic.AtomicLong

class PushSyncTrigger(
    private val connectionManager: ConnectionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastSyncAt = AtomicLong(0L)

    fun requestSync(reason: String) {
        val now = System.currentTimeMillis()
        val previous = lastSyncAt.get()
        if (now - previous < MIN_SYNC_GAP_MS) return
        if (!lastSyncAt.compareAndSet(previous, now)) return

        scope.launch {
            Log.d(TAG, "Triggering TDLib sync from push: $reason")
            connectionManager.retryConnection()
        }
    }

    private companion object {
        const val TAG = "PushSyncTrigger"
        const val MIN_SYNC_GAP_MS = 1500L
    }
}
