package org.monogram.push

import org.monogram.MonogramApp
import org.monogram.core.common.AppLog
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class MonogramUnifiedPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val app = application as? MonogramApp ?: return
        if (!app.awaitReadyBlocking()) return
        val keys = endpoint.pubKeySet
        app.push.onWebPushEndpoint(endpoint.url, keys?.pubKey, keys?.auth)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val app = application as? MonogramApp ?: return
        if (!app.awaitReadyBlocking()) return
        AppLog.api("unifiedpush", "wake")
        val text = message.content.toString(Charsets.UTF_8)
        if (text.startsWith("{") || text.contains("loc_key")) {
            app.push.handleFcm(text)
        } else {
            app.push.handleWake()
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        AppLog.warn("unifiedpush", "registration failed")
        val app = application as? MonogramApp ?: return
        if (!app.awaitReadyBlocking()) return
        app.notifications.setLastRegister("unifiedpush failed")
    }

    override fun onUnregistered(instance: String) {
        val app = application as? MonogramApp ?: return
        if (!app.awaitReadyBlocking()) return
        app.notifications.clearPushIdentity()
    }
}
