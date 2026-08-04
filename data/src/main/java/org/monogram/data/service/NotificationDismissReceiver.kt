package org.monogram.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.monogram.data.di.TdNotificationManager

class NotificationDismissReceiver : BroadcastReceiver(), KoinComponent {

    private val notificationManager: TdNotificationManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getLongExtra("chat_id", 0L)
        val notificationId = intent.getIntExtra("notification_id", 0)
        if (chatId != 0L) {
            if (!notificationManager.consumeNotificationAction(
                    "dismiss",
                    chatId,
                    notificationId
                )
            ) return
            if (notificationId != 0) {
                notificationManager.removeNotification(chatId, notificationId)
            } else {
                notificationManager.clearHistory(chatId)
            }
        }
    }
}