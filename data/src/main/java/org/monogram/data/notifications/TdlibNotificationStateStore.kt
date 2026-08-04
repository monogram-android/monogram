package org.monogram.data.notifications

import org.drinkless.tdlib.TdApi

class TdlibNotificationStateStore {
    private data class GroupState(
        val id: Int,
        val chatId: Long,
        val notifications: LinkedHashMap<Int, TdApi.Notification>
    )

    private val groups = LinkedHashMap<Int, GroupState>()
    private val dismissedUpToByChat = LinkedHashMap<Long, Int>()
    private val dismissedNotificationIdsByChat = LinkedHashMap<Long, MutableSet<Int>>()
    private var hasNativeSync = false

    @Synchronized
    fun hasNativeSync(): Boolean = hasNativeSync

    @Synchronized
    fun fingerprint(): Long = groups.values.fold(17L) { hash, group ->
        var next = hash * 31 + group.id
        next = next * 31 + group.notifications.size
        group.notifications.keys.fold(next) { value, notificationId -> value * 31 + notificationId }
    }

    @Synchronized
    fun reset() {
        groups.clear()
        dismissedUpToByChat.clear()
        dismissedNotificationIdsByChat.clear()
        hasNativeSync = false
    }

    @Synchronized
    fun replaceAll(update: TdApi.UpdateActiveNotifications): Set<Long> {
        val previousChatIds = groups.values.mapTo(linkedSetOf()) { it.chatId }
        groups.clear()
        hasNativeSync = true

        update.groups.orEmpty().forEach { group ->
            val notifications = linkedMapOf<Int, TdApi.Notification>()
            group.notifications.orEmpty().forEach { notification ->
                upsertNotification(
                    chatId = group.chatId,
                    notifications = notifications,
                    notification = notification
                )
            }
            if (group.totalCount != 0 || notifications.isNotEmpty()) {
                groups[group.id] = GroupState(
                    id = group.id,
                    chatId = group.chatId,
                    notifications = notifications
                )
            }
        }

        val currentChatIds = groups.values.mapTo(linkedSetOf()) { it.chatId }
        dismissedUpToByChat.keys.retainAll(currentChatIds)
        dismissedNotificationIdsByChat.keys.retainAll(currentChatIds)
        return previousChatIds + currentChatIds
    }

    @Synchronized
    fun apply(update: TdApi.UpdateNotificationGroup): Set<Long> {
        hasNativeSync = true
        val affectedChatIds = linkedSetOf<Long>()
        val existing = groups[update.notificationGroupId]
        if (existing != null) {
            affectedChatIds += existing.chatId
        }

        val notifications = LinkedHashMap(existing?.notifications ?: linkedMapOf())
        val removedNotificationIds = update.removedNotificationIds ?: intArrayOf()
        removedNotificationIds.forEach { removedId ->
            notifications.remove(removedId)
            dismissedNotificationIdsByChat[update.chatId]?.remove(removedId)
        }
        update.addedNotifications.orEmpty().forEach { notification ->
            upsertNotification(
                chatId = update.chatId,
                notifications = notifications,
                notification = notification
            )
        }

        if (update.totalCount == 0 && notifications.isEmpty()) {
            groups.remove(update.notificationGroupId)
        } else {
            groups[update.notificationGroupId] = GroupState(
                id = update.notificationGroupId,
                chatId = update.chatId,
                notifications = notifications
            )
            affectedChatIds += update.chatId
        }

        return affectedChatIds
    }

    @Synchronized
    fun apply(update: TdApi.UpdateNotification): Set<Long> {
        hasNativeSync = true
        val group = groups[update.notificationGroupId] ?: return emptySet()
        upsertNotification(
            chatId = group.chatId,
            notifications = group.notifications,
            notification = update.notification
        )
        return setOf(group.chatId)
    }

    @Synchronized
    fun getChatNotifications(chatId: Long): List<TdApi.Notification> {
        val dismissedUpTo = dismissedUpToByChat[chatId] ?: Int.MIN_VALUE
        val dismissedIds = dismissedNotificationIdsByChat[chatId].orEmpty()
        return groups.values
            .asSequence()
            .filter { it.chatId == chatId }
            .flatMap { it.notifications.values.asSequence() }
            .filter { it.id > dismissedUpTo && it.id !in dismissedIds }
            .sortedBy { it.date }
            .toList()
    }

    @Synchronized
    fun clearChat(chatId: Long) {
        dismissChatUpToCurrentMax(chatId)
    }

    @Synchronized
    fun removeNotification(chatId: Long, notificationId: Int) {
        if (containsRawNotification(chatId, notificationId)) {
            dismissedNotificationIdsByChat.getOrPut(chatId) { linkedSetOf() } += notificationId
            return
        }

        dismissChatUpToCurrentMax(chatId)
    }

    private fun upsertNotification(
        chatId: Long,
        notifications: LinkedHashMap<Int, TdApi.Notification>,
        notification: TdApi.Notification
    ) {
        when (val type = notification.type) {
            is TdApi.NotificationTypeNewMessage -> {
                removePushNotificationsByMessageId(chatId, type.message?.id ?: 0L)
                removePushNotificationsByMessageId(notifications, type.message?.id ?: 0L)
                notifications[notification.id] = notification
            }

            is TdApi.NotificationTypeNewPushMessage -> {
                val messageId = type.messageId
                if (containsRealMessageWithId(chatId, messageId) || containsRealMessageWithId(
                        notifications,
                        messageId
                    )
                ) {
                    return
                }
                notifications[notification.id] = notification
            }

            else -> notifications[notification.id] = notification
        }
    }

    private fun containsRawNotification(chatId: Long, notificationId: Int): Boolean {
        return groups.values.any { group ->
            group.chatId == chatId && group.notifications.containsKey(notificationId)
        }
    }

    private fun dismissChatUpToCurrentMax(chatId: Long) {
        val maxId = groups.values
            .asSequence()
            .filter { it.chatId == chatId }
            .flatMap { it.notifications.values.asSequence() }
            .maxOfOrNull { it.id }
            ?: return

        val current = dismissedUpToByChat[chatId] ?: Int.MIN_VALUE
        if (maxId > current) {
            dismissedUpToByChat[chatId] = maxId
        }
        dismissedNotificationIdsByChat.remove(chatId)
    }

    private fun removePushNotificationsByMessageId(
        chatId: Long,
        messageId: Long
    ) {
        if (messageId == 0L) return
        groups.values
            .asSequence()
            .filter { it.chatId == chatId }
            .forEach { group ->
                removePushNotificationsByMessageId(group.notifications, messageId)
            }
    }

    private fun removePushNotificationsByMessageId(
        notifications: LinkedHashMap<Int, TdApi.Notification>,
        messageId: Long
    ) {
        if (messageId == 0L) return
        val idsToRemove = notifications.values
            .filter { notification ->
                val type = notification.type as? TdApi.NotificationTypeNewPushMessage
                type?.messageId == messageId
            }
            .map { it.id }

        idsToRemove.forEach(notifications::remove)
    }

    private fun containsRealMessageWithId(
        chatId: Long,
        messageId: Long
    ): Boolean {
        if (messageId == 0L) return false
        return groups.values
            .asSequence()
            .filter { it.chatId == chatId }
            .flatMap { it.notifications.values.asSequence() }
            .any { notification ->
                val type = notification.type as? TdApi.NotificationTypeNewMessage
                type?.message?.id == messageId
            }
    }

    private fun containsRealMessageWithId(
        notifications: LinkedHashMap<Int, TdApi.Notification>,
        messageId: Long
    ): Boolean {
        if (messageId == 0L) return false
        return notifications.values.any { notification ->
            val type = notification.type as? TdApi.NotificationTypeNewMessage
            type?.message?.id == messageId
        }
    }
}
