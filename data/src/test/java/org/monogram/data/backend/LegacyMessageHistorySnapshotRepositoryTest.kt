package org.monogram.data.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.chats.ChatCache
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageHistoryCursorModel
import org.monogram.domain.models.MessageHistorySnapshotRequest
import org.monogram.domain.models.MessageModel
import org.monogram.domain.repository.BoundaryState
import org.monogram.domain.repository.HistoryAnchor
import org.monogram.domain.repository.HistoryDirection
import org.monogram.domain.repository.HistoryPage
import org.monogram.domain.repository.HistoryRequest
import org.monogram.domain.repository.HistorySource
import org.monogram.domain.repository.MessageRepository
import java.lang.reflect.Proxy

class LegacyMessageHistorySnapshotRepositoryTest {
    @Test
    fun `resolves neutral channel and preserves long cursor and message ids`() = runBlocking {
        val cache = ChatCache().apply { putChat(channelChat(chatId = -100_777L, channelId = 777L)) }
        val longMessageId = Int.MAX_VALUE.toLong() + 42L
        var captured: HistoryRequest? = null
        val messages = listOf(
            MessageModel(
                id = longMessageId,
                date = 120,
                isOutgoing = true,
                senderName = "Alice",
                senderId = 9,
                chatId = -100_777L,
                content = MessageContent.Document(null, "doc.pdf", "application/pdf", 10, "caption"),
                editDate = 121,
                mediaAlbumId = 88,
                isPinned = true,
                hasUnreadMention = true,
            )
        )
        val repository = repository(cache, messageRepository { request ->
            captured = request
            HistoryPage(messages, BoundaryState.Open, BoundaryState.Open, HistorySource.TdlibNetwork)
        })

        val page = repository.getHistory(
            MessageHistorySnapshotRequest(
                accountId = "default",
                peerType = DialogPeerType.CHANNEL,
                peerId = 777,
                before = MessageHistoryCursorModel(130, longMessageId + 1),
                limit = 1,
            )
        )

        assertEquals(-100_777L, captured?.key?.chatId)
        assertEquals(HistoryDirection.Older, captured?.direction)
        assertEquals(HistoryAnchor.Message(longMessageId + 1), captured?.anchor)
        assertEquals(longMessageId, page.messages.single().messageId)
        assertEquals(9L, page.messages.single().senderId)
        assertEquals("caption", page.messages.single().text)
        assertTrue(page.messages.single().hasMedia)
        assertTrue(page.messages.single().isMentioned)
        assertTrue(page.messages.single().isPinned)
        assertFalse(page.messages.single().isDeleted)
        assertFalse(page.messages.single().isMediaUnread)
        assertEquals(MessageHistoryCursorModel(120, longMessageId), page.nextCursor)
    }

    @Test
    fun `maps latest text page without inventing absent identifiers`() = runBlocking {
        val cache = ChatCache().apply { putChat(privateChat(chatId = 45, userId = 5)) }
        var captured: HistoryRequest? = null
        val repository = repository(cache, messageRepository { request ->
            captured = request
            HistoryPage(
                messages = listOf(MessageModel(7, 10, false, "Unknown", 45, MessageContent.Text("hello"))),
                olderBoundary = BoundaryState.Reached,
                newerBoundary = BoundaryState.Open,
                source = HistorySource.TdlibNetwork,
            )
        })

        val page = repository.getHistory(
            MessageHistorySnapshotRequest("default", DialogPeerType.PRIVATE, 5, limit = 2)
        )

        assertEquals(HistoryAnchor.Latest, captured?.anchor)
        assertEquals(HistoryDirection.Initial, captured?.direction)
        assertEquals("hello", page.messages.single().text)
        assertNull(page.messages.single().senderId)
        assertNull(page.messages.single().editDate)
        assertNull(page.messages.single().groupedId)
        assertNull(page.nextCursor)
    }

    @Test
    fun `rejects invalid pagination and mismatched peer before history read`() {
        val cache = ChatCache().apply { putChat(channelChat(chatId = 10, channelId = 20)) }
        var historyCalls = 0
        val repository = repository(cache, messageRepository {
            historyCalls++
            error("not expected")
        })

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.getHistory(MessageHistorySnapshotRequest("default", DialogPeerType.CHANNEL, 20, limit = 0)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.getHistory(
                    MessageHistorySnapshotRequest(
                        "default",
                        DialogPeerType.CHANNEL,
                        20,
                        before = MessageHistoryCursorModel(-1, 4),
                    )
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.getHistory(MessageHistorySnapshotRequest("default", DialogPeerType.SUPERGROUP, 20)) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.getHistory(MessageHistorySnapshotRequest("other", DialogPeerType.CHANNEL, 20)) }
        }
        assertEquals(0, historyCalls)
    }

    private fun repository(cache: ChatCache, messages: MessageRepository) = LegacyMessageHistorySnapshotRepository(
        accessGuard = LegacyBackendAccessGuard(LegacyActiveAccountBinding(), LegacySelectionStore()),
        messageRepository = messages,
        chatCache = cache,
    )

    private fun messageRepository(load: suspend (HistoryRequest) -> HistoryPage): MessageRepository =
        Proxy.newProxyInstance(
            MessageRepository::class.java.classLoader,
            arrayOf(MessageRepository::class.java),
        ) { _, method, args ->
            if (method.name == "getHistoryPage") {
                runBlocking { load(args[0] as HistoryRequest) }
            } else {
                error("Unexpected MessageRepository call: ${method.name}")
            }
        } as MessageRepository

    private class LegacySelectionStore : TelegramBackendSelectionStore {
        override suspend fun get(accountId: String) = TelegramBackendKind.LEGACY
        override fun observe(accountId: String): Flow<TelegramBackendKind> = error("not used")
        override suspend fun select(accountId: String, backend: TelegramBackendKind) = Unit
        override suspend fun reset(accountId: String) = Unit
    }

    private fun privateChat(chatId: Long, userId: Long) = TdApi.Chat().apply {
        id = chatId
        type = TdApi.ChatTypePrivate(userId)
    }

    private fun channelChat(chatId: Long, channelId: Long) = TdApi.Chat().apply {
        id = chatId
        type = TdApi.ChatTypeSupergroup(channelId, true)
    }
}
