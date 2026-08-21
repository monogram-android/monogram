package org.monogram.data.service

/**
 * Handles Android notification actions without exposing the TDLib notification implementation to
 * receivers that can be invoked after the selected account changes backend.
 */
interface NotificationActionManager {
    companion object {
        const val KEY_TEXT_REPLY = "key_text_reply"
    }
    fun consumeNotificationAction(action: String, chatId: Long, notificationId: Int): Boolean
    fun clearHistory(chatId: Long)
    fun removeNotification(chatId: Long, notificationId: Int)
}
