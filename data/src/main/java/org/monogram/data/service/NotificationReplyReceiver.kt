package org.monogram.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.monogram.domain.repository.MessageRepository

class NotificationReplyReceiver : BroadcastReceiver(), KoinComponent {

    private val messages: MessageRepository by inject()
    private val notificationManager: NotificationActionManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getLongExtra("chat_id", 0L)
        val notificationId = intent.getIntExtra("notification_id", 0)
        if (chatId == 0L) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = remoteInput.getCharSequence(NotificationActionManager.KEY_TEXT_REPLY)?.toString() ?: return
        if (!notificationManager.consumeNotificationAction("reply", chatId, notificationId)) return

        goAsync {
            try {
                launch {
                    runCatching { messages.sendChatAction(chatId, MessageRepository.ChatAction.Typing) }
                }
                messages.sendMessage(chatId, replyText)

                if (notificationId != 0) {
                    notificationManager.removeNotification(chatId, notificationId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}