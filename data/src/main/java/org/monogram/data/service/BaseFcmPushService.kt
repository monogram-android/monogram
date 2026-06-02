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
import org.monogram.data.gateway.TelegramGateway
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider

class BaseFcmPushService(
    private val context: Context,
    private val gateway: TelegramGateway,
    private val appPreferences: AppPreferencesProvider
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

            wakeLock.acquire(10_000L)
            scope.launch {
                try {
                    withTimeout(8_000L) {
                        gateway.execute(TdApi.ProcessPushNotification(jsonPayload))
                    }
                    Log.d(TAG, "ProcessPushNotification success")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Error processing push", e)
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

    private companion object {
        const val TAG = "FcmPushService"
    }
}
