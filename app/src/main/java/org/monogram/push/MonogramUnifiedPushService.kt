package org.monogram.push

import org.monogram.MonogramApp
import org.monogram.core.common.AppLog
import org.monogram.core.common.push.NO_PUSH_STATUS
import org.monogram.core.common.push.PUSH_STATUS_DISTRIBUTOR_GONE
import org.monogram.core.common.push.PUSH_STATUS_UP_FAILED
import org.monogram.core.common.push.PushProviderMode
import org.monogram.core.models.PushTokenType
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        val latch = CountDownLatch(1)
        app.push.handleIncoming(fcm = false, message.content) { latch.countDown() }
        latch.await(20, TimeUnit.SECONDS)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        AppLog.warn("unifiedpush", "registration failed")
        val app = application as? MonogramApp ?: return
        if (!app.awaitReadyBlocking()) return
        app.notifications.setLastRegister(PUSH_STATUS_UP_FAILED)
    }

    override fun onUnregistered(instance: String) {
        val app = application as? MonogramApp ?: return
        if (!app.awaitReadyBlocking()) return
        val type = app.notifications.tokenType()
        val wasUnifiedPush = type == PushTokenType.Simple.code || type == PushTokenType.WebPush.code
        if (!wasUnifiedPush) return
        val mode = app.notifications.providerMode
        app.notifications.clearPushIdentity()
        app.notifications.setLastRegister(
            if (mode == PushProviderMode.ForceUnifiedPush) PUSH_STATUS_DISTRIBUTOR_GONE else NO_PUSH_STATUS,
        )
    }
}
