package org.monogram.data.service

import android.util.Log
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.monogram.data.push.PushSyncTrigger
import org.monogram.data.push.UnifiedPushManager
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.PushProvider
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class UnifiedPushService : PushService(), KoinComponent {
    private val unifiedPushManager: UnifiedPushManager by inject()
    private val pushSyncTrigger: PushSyncTrigger by inject()
    private val appPreferences: AppPreferencesProvider by inject()

    override fun onMessage(message: PushMessage, instance: String) {
        val payload =
            runCatching { message.content.toString(Charsets.UTF_8) }.getOrDefault("<binary>")
        Log.d(
            TAG,
            "onMessage instance=$instance decrypted=${message.decrypted} bytes=${message.content.size} payload=${
                payload.take(
                    96
                )
            }"
        )

        if (!isUnifiedPushSelected()) {
            Log.d(TAG, "Ignore UnifiedPush message: provider is not UnifiedPush")
            return
        }

        val reason =
            if (payload.startsWith("version=")) "unified_push_telegram_simple" else "unified_push_message"
        pushSyncTrigger.requestSync(reason)
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Log.d(
            TAG,
            "onNewEndpoint instance=$instance temporary=${endpoint.temporary} hasPubKeySet=${endpoint.pubKeySet != null} url=${
                endpoint.url.take(
                    120
                )
            }"
        )

        unifiedPushManager.onNewEndpoint(endpoint)

        if (!isUnifiedPushSelected()) return
        pushSyncTrigger.requestSync("unified_push_new_endpoint")
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
