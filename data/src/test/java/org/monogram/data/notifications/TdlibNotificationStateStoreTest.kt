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
                    notifications = arrayOf(pushNotification(10, 1), pushNotification(11, 2))
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
                        notifications = arrayOf(pushNotification(10, 1), pushNotification(11, 2))
                    )
                )
            }
        )

        val affectedChats = store.apply(
            TdApi.UpdateNotificationGroup().apply {
                notificationGroupId = 1
                chatId = 100L
                totalCount = 2
                addedNotifications = arrayOf(pushNotification(12, 3))
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
                        notifications = arrayOf(pushNotification(10, 1))
                    )
                )
            }
        )

        store.reset()

        assertFalse(store.hasNativeSync())
        assertTrue(store.getChatNotifications(100L).isEmpty())
    }

    @Test
    fun `new message replaces push notification with same message id`() {
        store.replaceAll(
            TdApi.UpdateActiveNotifications().apply {
                groups = arrayOf(
                    notificationGroup(
                        groupId = 1,
                        chatId = 100L,
                        notifications = arrayOf(
                            pushNotification(
                                id = 10,
                                date = 1,
                                messageId = 500L
                            )
                        )
                    )
                )
            }
        )

        store.apply(
            TdApi.UpdateNotificationGroup().apply {
                notificationGroupId = 1
                chatId = 100L
                totalCount = 1
                addedNotifications =
                    arrayOf(messageNotification(id = 11, date = 2, messageId = 500L))
                removedNotificationIds = intArrayOf()
            }
        )

        val notifications = store.getChatNotifications(100L)

        assertEquals(listOf(11), notifications.map { it.id })
        assertTrue(notifications.single().type is TdApi.NotificationTypeNewMessage)
    }

    @Test
    fun `clear chat hides current notifications until newer ones arrive`() {
        store.replaceAll(
            TdApi.UpdateActiveNotifications().apply {
                groups = arrayOf(
                    notificationGroup(
                        groupId = 1,
                        chatId = 100L,
                        notifications = arrayOf(
                            pushNotification(id = 10, date = 1, messageId = 500L),
                            pushNotification(id = 11, date = 2, messageId = 501L)
                        )
                    )
                )
            }
        )

        store.clearChat(100L)

        assertTrue(store.getChatNotifications(100L).isEmpty())

        store.apply(
            TdApi.UpdateNotificationGroup().apply {
                notificationGroupId = 1
                chatId = 100L
                totalCount = 3
                addedNotifications = arrayOf(pushNotification(id = 12, date = 3, messageId = 502L))
                removedNotificationIds = intArrayOf()
            }
        )

        assertEquals(listOf(12), store.getChatNotifications(100L).map { it.id })
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

    private fun pushNotification(
        id: Int,
        date: Int,
        messageId: Long = id.toLong()
    ) = TdApi.Notification().apply {
        this.id = id
        this.date = date
        isSilent = false
        type = TdApi.NotificationTypeNewPushMessage().apply {
            this.messageId = messageId
            senderId = TdApi.MessageSenderUser(1L)
            senderName = "Push sender"
            content = TdApi.PushMessageContentText("push-$messageId", false)
        }
    }

    private fun messageNotification(
        id: Int,
        date: Int,
        messageId: Long
    ) = TdApi.Notification().apply {
        this.id = id
        this.date = date
        isSilent = false
        type = TdApi.NotificationTypeNewMessage().apply {
            message = TdApi.Message().apply {
                this.id = messageId
                chatId = 100L
                senderId = TdApi.MessageSenderUser(1L)
                this.date = date
                content = TdApi.MessageText().apply {
                    text = TdApi.FormattedText("message-$messageId", emptyArray())
                }
            }
        }
    }
}
