package org.monogram.data.notifications

import org.drinkless.tdlib.TdApi

class TdlibNotificationStateStore {
    private data class GroupState(
        val id: Int,
        val chatId: Long,
        val notifications: LinkedHashMap<Int, TdApi.Notification>
    )

    private val groups = LinkedHashMap<Int, GroupState>()
    private var hasNativeSync = false

    @Synchronized
    fun hasNativeSync(): Boolean = hasNativeSync

    @Synchronized
    fun reset() {
        groups.clear()
        hasNativeSync = false
    }

    @Synchronized
    fun replaceAll(update: TdApi.UpdateActiveNotifications): Set<Long> {
        val previousChatIds = groups.values.mapTo(linkedSetOf()) { it.chatId }
        groups.clear()
        hasNativeSync = true

        update.groups.orEmpty().forEach { group ->
            groups[group.id] = GroupState(
                id = group.id,
                chatId = group.chatId,
                notifications = linkedMapOf<Int, TdApi.Notification>().apply {
                    group.notifications.orEmpty().forEach { notification ->
                        put(notification.id, notification)
                    }
                }
            )
        }

        val currentChatIds = groups.values.mapTo(linkedSetOf()) { it.chatId }
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

        val notifications =
            LinkedHashMap<Int, TdApi.Notification>(existing?.notifications ?: linkedMapOf())
        update.removedNotificationIds.forEach { removedId ->
            notifications.remove(removedId)
        }
        update.addedNotifications.orEmpty().forEach { notification ->
            notifications[notification.id] = notification
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
        group.notifications[update.notification.id] = update.notification
        return setOf(group.chatId)
    }

    @Synchronized
    fun getChatNotifications(chatId: Long): List<TdApi.Notification> {
        return groups.values
            .asSequence()
            .filter { it.chatId == chatId }
            .flatMap { it.notifications.values.asSequence() }
            .sortedBy { it.date }
            .toList()
    }

    @Synchronized
    fun clearChat(chatId: Long) {
        val iterator = groups.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.chatId == chatId) {
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun removeNotification(chatId: Long, notificationId: Int) {
        val emptyGroupIds = mutableListOf<Int>()
        groups.forEach { (groupId, group) ->
            if (group.chatId != chatId) return@forEach
            group.notifications.remove(notificationId)
            if (group.notifications.isEmpty()) {
                emptyGroupIds += groupId
            }
        }
        emptyGroupIds.forEach(groups::remove)
    }
}
