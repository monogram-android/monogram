package org.monogram.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.MessageRepository

class NotificationReadReceiver : BroadcastReceiver(), KoinComponent {

    private val messages: MessageRepository by inject()
    private val dialogs: DialogSnapshotRepository by inject()
    private val notificationManager: NotificationActionManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getLongExtra("chat_id", 0L)
        val notificationId = intent.getIntExtra("notification_id", 0)
        if (chatId == 0L) return
        if (!notificationManager.consumeNotificationAction("read", chatId, notificationId)) return

        goAsync {
            try {
                if (notificationId != 0) {
                    notificationManager.removeNotification(chatId, notificationId)
                } else {
                    notificationManager.clearHistory(chatId)
                }

                val dialog = dialogs.getDialogs(DEFAULT_ACCOUNT_ID).firstOrNull {
                    TelegramPeerChatId.encode(it.peerType, it.peerId) == chatId
                }
                    ?: run {
                        Log.w("NotificationReadReceiver", "Chat $chatId not found")
                        return@goAsync
                    }
                if (dialog.unreadCount > 0 && dialog.latestMessage.messageId > 0) {
                    messages.markAsRead(chatId, dialog.latestMessage.messageId)
                }
            } catch (e: Exception) {
                Log.e("NotificationReadReceiver", "Failed to mark messages as read", e)
            }
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
