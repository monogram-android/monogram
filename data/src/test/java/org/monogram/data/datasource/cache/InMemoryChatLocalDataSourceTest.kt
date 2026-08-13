package org.monogram.data.datasource.cache

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.MessageEntity
import org.monogram.data.db.model.MessageWindowEntity

class InMemoryChatLocalDataSourceTest {

    @Test
    fun `history snapshot does not overwrite newer live mutation`() = runTest {
        val source = InMemoryChatLocalDataSource()
        val live = message(id = 10L, chatId = 1L, date = 20, content = "edited", editDate = 20)
        val staleHistory =
            message(id = 10L, chatId = 1L, date = 20, content = "original", editDate = 0)

        source.insertMessage(live)
        source.applyMessageCacheMutations(
            listOf(
                MessageCacheMutation.PersistHistoryBatch(
                    writes = listOf(
                        MessageCacheMutation.HistoryWrite(
                            staleHistory,
                            expectedExisting = null
                        )
                    ),
                    window = messageWindow(chatId = 1L)
                )
            )
        )

        assertEquals(
            live,
            source.getMessagesByIds(chatId = 1L, messageIds = listOf(10L)).single()
        )
    }

    @Test
    fun `history snapshot refreshes unchanged stale cache row`() = runTest {
        val source = InMemoryChatLocalDataSource()
        val stale = message(id = 10L, chatId = 1L, content = "old", editDate = 0)
        val refreshed = message(id = 10L, chatId = 1L, content = "new", editDate = 10)
        source.insertMessage(stale)

        source.applyMessageCacheMutations(
            listOf(
                MessageCacheMutation.PersistHistoryBatch(
                    writes = listOf(
                        MessageCacheMutation.HistoryWrite(
                            refreshed,
                            expectedExisting = stale
                        )
                    ),
                    window = messageWindow(chatId = 1L)
                )
            )
        )

        assertEquals(refreshed, source.getMessagesByIds(1L, listOf(10L)).single())
    }
    @Test
    fun `getStartupChats filters zero order sorts and limits`() = runBlocking {
        val source = InMemoryChatLocalDataSource()
        source.insertChats(
            listOf(
                chat(id = 1L, order = 0L, isPinned = true),
                chat(id = 2L, order = 10L, isPinned = false),
                chat(id = 3L, order = 5L, isPinned = true),
                chat(id = 4L, order = 20L, isPinned = false)
            )
        )

        val result = source.getStartupChats(limit = 2)

        assertEquals(listOf(3L, 4L), result.map { it.id })
    }

    @Test
    fun `replaceMessageId removes pending id and keeps final message`() = runBlocking {
        val source = InMemoryChatLocalDataSource()
        val pending = message(id = -10L, chatId = 1L, content = "pending")
        val sent = message(id = 20L, chatId = 1L, content = "sent")

        source.insertMessage(pending)
        source.replaceMessageId(chatId = 1L, oldMessageId = -10L, message = sent)

        val result = source.getLatestMessages(chatId = 1L, limit = 10)

        assertEquals(listOf(20L), result.map { it.id })
        assertEquals("sent", result.single().content)
    }

    @Test
    fun `getMessagesAround includes target with older and newer cached messages`() = runBlocking {
        val source = InMemoryChatLocalDataSource()
        listOf(10L, 20L, 30L, 40L, 50L).forEach { id ->
            source.insertMessage(
                message(
                    id = id,
                    chatId = 1L,
                    content = "message $id",
                    date = id.toInt()
                )
            )
        }

        val result = source.getMessagesAround(chatId = 1L, messageId = 30L, limit = 5)

        assertEquals(listOf(50L, 40L, 30L, 20L, 10L), result.map { it.id })
    }

    @Test
    fun `getMessagesNewer returns ascending cached page`() = runBlocking {
        val source = InMemoryChatLocalDataSource()
        listOf(10L, 20L, 30L, 40L).forEach { id ->
            source.insertMessage(
                message(
                    id = id,
                    chatId = 1L,
                    content = "message $id",
                    date = id.toInt()
                )
            )
        }

        val result = source.getMessagesNewer(chatId = 1L, fromMessageId = 20L, limit = 2)

        assertEquals(listOf(30L, 40L), result.map { it.id })
    }

    @Test
    fun `replaceMessage preserves ordering and replaces rich fields`() = runBlocking {
        val source = InMemoryChatLocalDataSource()
        source.insertMessage(
            message(
                id = 100L,
                chatId = 1L,
                content = "old",
                date = 100,
                replyMarkupData = "markup-old",
                reactionsData = "reactions-old",
                isPinned = false,
                hasUnreadReactions = false
            )
        )
        source.insertMessage(message(id = 200L, chatId = 1L, content = "newer", date = 200))

        source.replaceMessage(
            message(
                id = 100L,
                chatId = 1L,
                content = "updated",
                date = 100,
                replyMarkupData = "markup-new",
                reactionsData = "reactions-new",
                isPinned = true,
                hasUnreadReactions = true
            )
        )

        val result = source.getLatestMessages(chatId = 1L, limit = 10)
        val replaced = result.last { it.id == 100L }

        assertEquals(listOf(200L, 100L), result.map { it.id })
        assertEquals("updated", replaced.content)
        assertEquals("markup-new", replaced.replyMarkupData)
        assertEquals("reactions-new", replaced.reactionsData)
        assertTrue(replaced.isPinned)
        assertTrue(replaced.hasUnreadReactions)
        assertFalse(replaced.replyMarkupData == "markup-old")
    }

    private fun chat(id: Long, order: Long, isPinned: Boolean): ChatEntity =
        ChatEntity(
            id = id,
            title = "chat $id",
            unreadCount = 0,
            avatarPath = null,
            lastMessageText = "",
            lastMessageTime = "",
            order = order,
            isPinned = isPinned,
            isMuted = false,
            isChannel = false,
            isGroup = false,
            type = "PRIVATE",
            isArchived = false,
            memberCount = 0,
            onlineCount = 0
        )

    private fun message(
        id: Long,
        chatId: Long,
        content: String,
        date: Int = 100,
        replyMarkupData: String? = null,
        reactionsData: String? = null,
        isPinned: Boolean = false,
        hasUnreadReactions: Boolean = false,
        editDate: Int = 0
    ): MessageEntity =
        MessageEntity(
            id = id,
            chatId = chatId,
            senderId = 1L,
            senderName = "sender",
            content = content,
            date = date,
            isOutgoing = true,
            isRead = false,
            replyMarkupData = replyMarkupData,
            reactionsData = reactionsData,
            editDate = editDate,
            isPinned = isPinned,
            hasUnreadReactions = hasUnreadReactions
        )

    private fun messageWindow(chatId: Long) = MessageWindowEntity(
        chatId = chatId,
        scopeType = "main",
        scopeId = 0L,
        oldestMessageId = null,
        newestMessageId = null,
        olderBoundaryReached = false,
        newerBoundaryReached = false,
        lastTdlibSyncAt = 0L,
        generation = 1L
    )
}
