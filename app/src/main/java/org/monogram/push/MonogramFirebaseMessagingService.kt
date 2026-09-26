package org.monogram.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.monogram.MonogramApp
import org.monogram.core.common.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MonogramFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = application as? MonogramApp ?: return
        if (!app.awaitReadyBlocking()) return
        app.push.onFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as? MonogramApp ?: return
        if (!app.awaitReadyBlocking()) return
        AppLog.api("fcm", "data keys=${message.data.keys.joinToString()}")
        val latch = CountDownLatch(1)
        try {
            app.push.handleFcm(message.data["p"])
        } finally {
            latch.countDown()
        }
        latch.await(20, TimeUnit.SECONDS)
    }
}
