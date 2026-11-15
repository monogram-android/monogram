package org.monogram.push

import org.monogram.MonogramApp
import org.monogram.core.common.AppLog
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MonogramUnifiedPushService : PushService() {
    private fun coordinator(instance: String): PushCoordinator? {
        val app = application as? MonogramApp ?: return null
        if (!app.awaitPushReadyBlocking()) return null
        val userId = instance.removePrefix("account:").toLongOrNull() ?: return null
        if (userId == 0L || instance != "account:$userId") return null
        val coordinator = app.pushRouter.coordinator(userId) as? PushCoordinator ?: return null
        return coordinator.takeIf { it.ownsUnifiedPushInstance(instance) }
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val coordinator = coordinator(instance) ?: return
        val keys = endpoint.pubKeySet
        coordinator.onWebPushEndpoint(endpoint.url, keys?.pubKey, keys?.auth)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val coordinator = coordinator(instance) ?: return
        AppLog.api("unifiedpush", "wake")
        val latch = CountDownLatch(1)
        coordinator.handleIncoming(fcm = false, message.content) { latch.countDown() }
        latch.await(20, TimeUnit.SECONDS)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        val coordinator = coordinator(instance) ?: return
        AppLog.warn("unifiedpush", "registration failed")
        coordinator.onUnifiedPushRegistrationFailed()
    }

    override fun onUnregistered(instance: String) {
        coordinator(instance)?.onUnifiedPushUnregistered()
    }
}
