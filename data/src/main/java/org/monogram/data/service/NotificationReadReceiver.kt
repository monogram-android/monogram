package org.monogram.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.drinkless.tdlib.TdApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.monogram.data.di.TdNotificationManager
import org.monogram.data.gateway.TdLibException
import org.monogram.data.gateway.TelegramGateway

class NotificationReadReceiver : BroadcastReceiver(), KoinComponent {

    private val gateway: TelegramGateway by inject()
    private val notificationManager: TdNotificationManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getLongExtra("chat_id", 0L)
        val notificationId = intent.getIntExtra("notification_id", 0)
        if (chatId == 0L) return

        goAsync {
            try {
                if (notificationId != 0) {
                    notificationManager.removeNotification(chatId, notificationId)
                } else {
                    notificationManager.clearHistory(chatId)
                }

                val chat = try {
                    gateway.execute(TdApi.GetChat(chatId))
                } catch (e: TdLibException) {
                    if (e.error.message == "Not Found") {
                        Log.w("NotificationReadReceiver", "Chat $chatId not found")
                        return@goAsync
                    }
                    throw e
                }

                if (chat.unreadCount > 0) {
                    chat.lastMessage?.let { lastMessage ->
                        try {
                            gateway.execute(TdApi.ViewMessages(chatId, longArrayOf(lastMessage.id), null, true))
                        } catch (e: TdLibException) {
                            if (e.error.message == "Message is too old" || e.error.message == "Not Found") {
                                Log.w("NotificationReadReceiver", "Failed to mark message as read: ${e.error.message}")
                            } else {
                                throw e
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("NotificationReadReceiver", "Failed to mark messages as read", e)
            }
        }
    }
}
