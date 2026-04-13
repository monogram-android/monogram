package org.monogram.data.push

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.ConnectionManager
import java.util.concurrent.atomic.AtomicLong

class PushSyncTrigger(
    private val connectionManager: ConnectionManager,
    private val gateway: TelegramGateway
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastSyncAt = AtomicLong(0L)

    fun requestSync(reason: String) {
        val now = System.currentTimeMillis()
        val previous = lastSyncAt.get()
        if (now - previous < MIN_SYNC_GAP_MS) {
            Log.d(TAG, "Skip push sync by rate limit: reason=$reason")
            return
        }
        if (!lastSyncAt.compareAndSet(previous, now)) {
            Log.d(TAG, "Skip push sync by CAS race: reason=$reason")
            return
        }

        scope.launch {
            if (!gateway.isAuthenticated.value) {
                Log.d(TAG, "Skip push sync: not authenticated, reason=$reason")
                return@launch
            }

            Log.d(TAG, "Triggering TDLib sync from push: reason=$reason")
            connectionManager.retryConnection()

            delay(PUSH_SYNC_DELAY_MS)

            val me = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching { gateway.execute(TdApi.GetMe()) }.getOrNull()
            }
            if (me != null) {
                Log.d(TAG, "Push sync probe success: me=${me.id}")
            } else {
                Log.w(TAG, "Push sync probe failed (GetMe timeout/error)")
            }
        }
    }

    private companion object {
        const val TAG = "PushSyncTrigger"
        const val MIN_SYNC_GAP_MS = 1500L
        const val PUSH_SYNC_DELAY_MS = 350L
        const val REQUEST_TIMEOUT_MS = 5000L
    }
}
