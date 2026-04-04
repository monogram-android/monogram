package org.monogram.data.service

import android.os.PowerManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import org.koin.android.ext.android.inject
import org.monogram.data.gateway.TelegramGateway
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider

class FcmPushService : FirebaseMessagingService() {
    private val gateway: TelegramGateway by inject()
    private val appPreferences: AppPreferencesProvider by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FcmPushService", "New FCM token: $token")
        if (appPreferences.pushProvider.value == PushProvider.FCM) {
            scope.launch {
                registerToken(token)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d("FcmPushService", "FCM message received: ${message.data}")

        if (appPreferences.pushProvider.value != PushProvider.FCM) return

        val data = message.data
        if (data.isNotEmpty()) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "monogram:FcmPushService")

            try {
                val json = JSONObject()
                for ((k, v) in data) {
                    json.put(k, v)
                }
                val jsonPayload = json.toString()

                wakeLock.acquire(10_000L)
                scope.launch {
                    try {
                        gateway.execute(TdApi.ProcessPushNotification(jsonPayload))
                        Log.d("FcmPushService", "ProcessPushNotification success")
                        delay(5000)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("FcmPushService", "Error processing push", e)
                    } finally {
                        if (wakeLock.isHeld) {
                            wakeLock.release()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FcmPushService", "Error processing push", e)
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()
        Log.d("FcmPushService", "FCM messages deleted")
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
            Log.d("FcmPushService", "RegisterDevice result: $result")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("FcmPushService", "RegisterDevice failed", e)
        }
    }
}