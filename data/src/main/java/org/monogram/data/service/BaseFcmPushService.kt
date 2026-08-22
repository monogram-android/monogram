package org.monogram.data.service

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.data.push.MtProtoPushSync
import org.monogram.domain.repository.PushProvider

internal class BaseFcmPushService(
    private val context: Context,
    private val appPreferences: AppPreferencesProvider,
    private val pushSync: MtProtoPushSync? = null,
) {
    fun handleNewToken(token: String) {
        Log.d(TAG, "New FCM token received")
        if (appPreferences.pushProvider.value == PushProvider.FCM && pushSync != null) {
            pushSync.requestSync("fcm_token_rotation")
        }
    }

    fun handleMessage(data: Map<String, String>) {
        Log.d(TAG, "FCM message received: keyCount=${data.size}")

        if (appPreferences.pushProvider.value != PushProvider.FCM) return
        if (pushSync == null) {
            Log.d(TAG, "Push payload ignored: no MTProto push sync configured")
            return
        }

        val accepted = pushSync.requestSync(reason = data["tag"] ?: "fcm_message")
        Log.d(TAG, "Push sync ${if (accepted) "enqueued" else "saturated"} (${data.size} keys)")
    }

    fun handleDeletedMessages() {
        Log.d(TAG, "FCM messages deleted")
        if (appPreferences.pushProvider.value == PushProvider.FCM && pushSync != null) {
            // Deleted pushes mean missed notifications; force a reconciliation round trip.
            pushSync.requestSync("fcm_deleted_messages")
        }
    }

    private companion object {
        const val TAG = "FcmPushService"
    }
}
