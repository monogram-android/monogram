package org.monogram.data.mtproto

import org.monogram.data.service.NotificationActionManager

/** No-op until the MTProto stack owns Android notification actions. */
internal class NoOpNotificationActionManager : NotificationActionManager {
    override fun consumeNotificationAction(action: String, chatId: Long, notificationId: Int): Boolean = false

    override fun clearHistory(chatId: Long) = Unit

    override fun removeNotification(chatId: Long, notificationId: Int) = Unit
}
