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
        if (!isUnifiedPushSelected()) return
        pushSyncTrigger.requestSync("unified_push_message")
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        if (!isUnifiedPushSelected()) return
        unifiedPushManager.onNewEndpoint(endpoint)
        pushSyncTrigger.requestSync("unified_push_new_endpoint")
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        unifiedPushManager.onRegistrationFailed(reason)
    }

    override fun onUnregistered(instance: String) {
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
