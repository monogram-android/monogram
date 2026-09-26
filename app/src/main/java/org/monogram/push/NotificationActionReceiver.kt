package org.monogram.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.monogram.MonogramApp

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MonogramApp ?: return
        val pending = goAsync()
        app.launchWhenReady {
            try {
                app.push.handleNotificationAction(intent) { pending.finish() }
            } catch (_: Throwable) {
                pending.finish()
            }
        }.invokeOnCompletion { error ->
            if (error != null) pending.finish()
        }
    }
}
