package org.monogram.data.service

import android.util.Log
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.monogram.data.push.MtProtoPushSync
import org.monogram.data.push.UnifiedPushManager
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class UnifiedPushService : PushService(), KoinComponent {
    private val unifiedPushManager: UnifiedPushManager by inject()
    private val appPreferences: AppPreferencesProvider by inject()
    private val pushSync: MtProtoPushSync by inject()

    override fun onMessage(message: PushMessage, instance: String) {
        Log.d(
            TAG,
            "onMessage instance=$instance decrypted=${message.decrypted} bytes=${message.content.size}"
        )

        if (!isUnifiedPushSelected()) {
            Log.d(TAG, "Ignore UnifiedPush message: provider is not UnifiedPush")
            return
        }

        unifiedPushManager.markPushReceived()
        val accepted = pushSync.requestSync("unified_push_message")
        Log.d(TAG, "Push sync ${if (accepted) "enqueued" else "saturated"} (bytes=${message.content.size})")
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Log.d(
            TAG,
            "onNewEndpoint instance=$instance temporary=${endpoint.temporary} hasPubKeySet=${endpoint.pubKeySet != null}"
        )

        unifiedPushManager.onNewEndpoint(endpoint)

        if (!isUnifiedPushSelected()) return
        pushSync.requestSync("unified_push_endpoint")
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "onRegistrationFailed instance=$instance reason=$reason")
        unifiedPushManager.onRegistrationFailed(reason)
    }

    override fun onUnregistered(instance: String) {
        Log.w(TAG, "onUnregistered instance=$instance")
        unifiedPushManager.onUnregistered()
    }

    override fun onTempUnavailable(instance: String) {
        unifiedPushManager.onTempUnavailable()
        Log.w(TAG, "UnifiedPush temp unavailable for instance=$instance")
    }

    private fun isUnifiedPushSelected(): Boolean {
        return appPreferences.pushProvider.value == PushProvider.UNIFIED_PUSH
    }

    private companion object {
        const val TAG = "UnifiedPushService"
    }
}
