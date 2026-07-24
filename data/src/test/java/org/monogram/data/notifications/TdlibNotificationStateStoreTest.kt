package org.monogram.data.notifications

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TdlibNotificationStateStoreTest {

    private val store = TdlibNotificationStateStore()

    @Test
    fun `replaceAll stores notifications and enables native sync`() {
        val update = TdApi.UpdateActiveNotifications().apply {
            groups = arrayOf(
                notificationGroup(
                    groupId = 1,
                    chatId = 100L,
                    notifications = arrayOf(notification(10, 1), notification(11, 2))
                )
            )
        }

        val affectedChats = store.replaceAll(update)

        assertTrue(store.hasNativeSync())
        assertEquals(setOf(100L), affectedChats)
        assertEquals(listOf(10, 11), store.getChatNotifications(100L).map { it.id })
    }

    @Test
    fun `group update removes and adds notifications`() {
        store.replaceAll(
            TdApi.UpdateActiveNotifications().apply {
                groups = arrayOf(
                    notificationGroup(
                        groupId = 1,
                        chatId = 100L,
                        notifications = arrayOf(notification(10, 1), notification(11, 2))
                    )
                )
            }
        )

        val affectedChats = store.apply(
            TdApi.UpdateNotificationGroup().apply {
                notificationGroupId = 1
                chatId = 100L
                totalCount = 2
                addedNotifications = arrayOf(notification(12, 3))
                removedNotificationIds = intArrayOf(10)
            }
        )

        assertEquals(setOf(100L), affectedChats)
        assertEquals(listOf(11, 12), store.getChatNotifications(100L).map { it.id })
    }

    @Test
    fun `reset clears notifications and disables native sync`() {
        store.replaceAll(
            TdApi.UpdateActiveNotifications().apply {
                groups = arrayOf(
                    notificationGroup(
                        groupId = 1,
                        chatId = 100L,
                        notifications = arrayOf(notification(10, 1))
                    )
                )
            }
        )

        store.reset()

        assertFalse(store.hasNativeSync())
        assertTrue(store.getChatNotifications(100L).isEmpty())
    }

    private fun notificationGroup(
        groupId: Int,
        chatId: Long,
        notifications: Array<TdApi.Notification>
    ) = TdApi.NotificationGroup().apply {
        id = groupId
        this.chatId = chatId
        totalCount = notifications.size
        this.notifications = notifications
        type = TdApi.NotificationGroupTypeMessages()
    }

    private fun notification(
        id: Int,
        date: Int
    ) = TdApi.Notification().apply {
        this.id = id
        this.date = date
        isSilent = false
        type = TdApi.NotificationTypeNewPushMessage()
    }
}
