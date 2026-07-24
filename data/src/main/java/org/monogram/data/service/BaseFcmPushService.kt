package org.monogram.data.service

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import org.monogram.data.di.TdNotificationManager
import org.monogram.data.gateway.TdLibException
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.push.PushSyncTrigger
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider

class BaseFcmPushService(
    private val context: Context,
    private val gateway: TelegramGateway,
    private val appPreferences: AppPreferencesProvider,
    private val notificationManager: TdNotificationManager,
    private val pushSyncTrigger: PushSyncTrigger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handleNewToken(token: String) {
        Log.d(TAG, "New FCM token: $token")
        if (appPreferences.pushProvider.value == PushProvider.FCM) {
            scope.launch {
                registerToken(token)
            }
        }
    }

    fun handleMessage(data: Map<String, String>) {
        Log.d(TAG, "FCM message received: $data")

        if (appPreferences.pushProvider.value != PushProvider.FCM) return
        if (data.isEmpty()) return

        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val wakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "monogram:FcmPushService")
                .apply { setReferenceCounted(false) }

        try {
            val json = JSONObject()
            for ((key, value) in data) {
                json.put(key, value)
            }
            val jsonPayload = json.toString()
            if (jsonPayload.isBlank()) return

            wakeLock.acquire(PUSH_WAKE_LOCK_MS)
            scope.launch {
                val notificationStateVersion = notificationManager.currentNotificationStateVersion()
                try {
                    withTimeout(PROCESS_PUSH_TIMEOUT_MS) {
                        gateway.execute(TdApi.ProcessPushNotification(jsonPayload))
                    }
                    Log.d(TAG, "ProcessPushNotification success")
                    val updated = notificationManager.awaitNotificationStateChange(
                        afterVersion = notificationStateVersion,
                        timeoutMs = NOTIFICATION_SETTLE_TIMEOUT_MS
                    )
                    if (!updated) {
                        Log.w(
                            TAG,
                            "No notification-state update after push, requesting sync fallback"
                        )
                        pushSyncTrigger.requestSync("fcm_push_settle_timeout")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Error processing push", e)
                    if (!e.isAuthRelatedPushError()) {
                        pushSyncTrigger.requestSync("fcm_push_process_error")
                    }
                } finally {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing push payload", e)
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    fun handleDeletedMessages() {
        Log.d(TAG, "FCM messages deleted")
    }

    private suspend fun registerToken(token: String) {
        if (!gateway.isAuthenticated.value) return

        try {
            val result = gateway.execute(
                TdApi.RegisterDevice(
                    TdApi.DeviceTokenFirebaseCloudMessaging(token, true),
                    longArrayOf()
                )
            )
            Log.d(TAG, "RegisterDevice result: $result")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "RegisterDevice failed", e)
        }
    }

    private fun Throwable.isAuthRelatedPushError(): Boolean {
        val tdError = (this as? TdLibException)?.error ?: return false
        val message = tdError.message.orEmpty().lowercase()
        return tdError.code == 401 ||
                message.contains("unauthorized") ||
                message.contains("authorization") ||
                message.contains("not logged in")
    }

    private companion object {
        const val TAG = "FcmPushService"
        const val PUSH_WAKE_LOCK_MS = 15_000L
        const val PROCESS_PUSH_TIMEOUT_MS = 8_000L
        const val NOTIFICATION_SETTLE_TIMEOUT_MS = 2_500L
    }
}
