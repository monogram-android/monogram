package org.monogram.data.service

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider

class BaseFcmPushService(
    private val context: Context,
    private val appPreferences: AppPreferencesProvider,
) {
    fun handleNewToken(token: String) {
        Log.d(TAG, "New FCM token received")
        if (appPreferences.pushProvider.value == PushProvider.FCM) {
            Log.d(TAG, "Token registration skipped: MTProto push sync is not wired yet")
        }
    }

    fun handleMessage(data: Map<String, String>) {
        Log.d(TAG, "FCM message received: keyCount=${data.size}")

        if (appPreferences.pushProvider.value != PushProvider.FCM) return
        if (data.isEmpty()) return

        try {
            val json = JSONObject()
            for ((key, value) in data) {
                json.put(key, value)
            }
            // Push payload processing is not wired to the MTProto stack yet.
            Log.d(TAG, "Push payload ignored (bytes=${json.toString().length})")
        } catch (_: Exception) {
        }
    }

    fun handleDeletedMessages() {
        Log.d(TAG, "FCM messages deleted")
        if (appPreferences.pushProvider.value == PushProvider.FCM) {
            Log.d(TAG, "Reconciliation skipped: MTProto push sync is not wired yet")
        }
    }

    private companion object {
        const val TAG = "FcmPushService"
    }
}
