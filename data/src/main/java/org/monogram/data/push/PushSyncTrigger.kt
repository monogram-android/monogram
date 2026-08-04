package org.monogram.data.push

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.ConnectionManager

interface PushSyncRequester {
    fun requestSync(reason: String)
}

class PushSyncTrigger(
    private val connectionManager: ConnectionManager,
    private val gateway: TelegramGateway
) : PushSyncRequester {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ConflatedSyncRequestQueue(
        scope = scope,
        minIntervalMs = MIN_SYNC_GAP_MS,
        execute = ::runSync
    )

    override fun requestSync(reason: String) {
        if (!requests.request(reason)) {
            Log.w(TAG, "Unable to enqueue push sync request")
        }
    }

    private suspend fun runSync(reason: String) {
        if (!gateway.isAuthenticated.value) {
            Log.d(TAG, "Skip push sync: not authenticated, reason=$reason")
            return
        }

        Log.d(TAG, "Triggering TDLib sync from push: reason=$reason")
        connectionManager.retryConnection()

        delay(PUSH_SYNC_DELAY_MS)
        Log.d(TAG, "Requested TDLib connection recovery: reason=$reason")
    }

    private companion object {
        const val TAG = "PushSyncTrigger"
        const val MIN_SYNC_GAP_MS = 1500L
        const val PUSH_SYNC_DELAY_MS = 350L
    }
}
