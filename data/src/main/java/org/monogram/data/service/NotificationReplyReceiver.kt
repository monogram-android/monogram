package org.monogram.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.monogram.data.di.TdNotificationManager
import org.monogram.data.gateway.TelegramGateway

class NotificationReplyReceiver : BroadcastReceiver(), KoinComponent {

    private val gateway: TelegramGateway by inject()
    private val notificationManager: TdNotificationManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getLongExtra("chat_id", 0L)
        val notificationId = intent.getIntExtra("notification_id", 0)
        if (chatId == 0L) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = remoteInput.getCharSequence(TdNotificationManager.KEY_TEXT_REPLY)?.toString() ?: return

        goAsync {
            try {
                val actionTyping = TdApi.SendChatAction().apply {
                    this.chatId = chatId
                    this.topicId = null
                    this.action = TdApi.ChatActionTyping()
                }

                launch {
                    runCatching { gateway.execute(actionTyping) }
                }

                val chat = gateway.execute(TdApi.GetChat(chatId))

                val inputMessageContent = TdApi.InputMessageText().apply {
                    this.text = TdApi.FormattedText(replyText, emptyArray())
                    this.clearDraft = true
                }

                val request = TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.replyTo = TdApi.InputMessageReplyToMessage()
                    this.options = TdApi.MessageSendOptions().apply {
                        this.disableNotification = false
                        this.fromBackground = true
                    }
                    this.inputMessageContent = inputMessageContent
                }

                gateway.execute(request)

                if (notificationId != 0) {
                    notificationManager.removeNotification(chatId, notificationId)
                }

                notificationManager.appendMessageToNotification(
                    chatId = chatId,
                    messageId = System.currentTimeMillis(),
                    chatType = chat.type,
                    senderName = "Вы",
                    senderBitmap = null,
                    chatIcon = null,
                    text = replyText,
                    timestamp = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}