package org.monogram.data.service

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.monogram.data.push.PushProcessingCoordinator
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider

class BaseFcmPushService(
    private val context: Context,
    private val appPreferences: AppPreferencesProvider,
    private val pushCoordinator: PushProcessingCoordinator
) {
    fun handleNewToken(token: String) {
        Log.d(TAG, "New FCM token received")
        if (appPreferences.pushProvider.value == PushProvider.FCM) {
            pushCoordinator.enqueueFcmTokenRegistration(token)
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
            val jsonPayload = json.toString()
            if (jsonPayload.isBlank()) return

            pushCoordinator.enqueue(PushProcessingCoordinator.Provider.FCM, jsonPayload)
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing push payload", e)
        }
    }

    fun handleDeletedMessages() {
        Log.d(TAG, "FCM messages deleted")
        if (appPreferences.pushProvider.value == PushProvider.FCM) {
            pushCoordinator.enqueueReconciliation()
        }
    }

    private companion object {
        const val TAG = "FcmPushService"
    }
}
