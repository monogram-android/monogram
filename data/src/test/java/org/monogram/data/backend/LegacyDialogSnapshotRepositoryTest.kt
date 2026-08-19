package org.monogram.data.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.chats.ChatCache
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ConnectionStatus

class LegacyDialogSnapshotRepositoryTest {
    @Test
    fun `maps loaded legacy chats and preserves flow order`() = runBlocking {
        val cache = ChatCache()
        cache.putChat(TdApiFixtures.privateChat(11, 101))
        cache.putChat(TdApiFixtures.channelChat(22, 202))
        val repository = LegacyDialogSnapshotRepository(
            accessGuard = LegacyBackendAccessGuard(LegacyActiveAccountBinding(), LegacySelectionStore()),
            chatListRepository = FakeChatListRepository(
                listOf(
                    chat(11, "Alice", "hello"),
                    chat(22, "News", "photo", contentType = "photo", lastMessageId = Int.MAX_VALUE.toLong() + 10),
                )
            ),
            chatCache = cache,
        )

        val dialogs = repository.getDialogs("default")

        assertEquals(listOf(101L, 202L), dialogs.map { it.peerId })
        assertEquals(DialogPeerType.PRIVATE, dialogs[0].peerType)
        assertEquals(DialogPeerType.CHANNEL, dialogs[1].peerType)
        assertEquals("photo", dialogs[1].latestMessage.text)
        assertEquals(Int.MAX_VALUE.toLong() + 10, dialogs[1].latestMessage.messageId)
        assertTrue(dialogs[1].latestMessage.hasMedia)
    }

    @Test
    fun `keeps chats missing from cache as unresolved unknown peers`() = runBlocking {
        val repository = LegacyDialogSnapshotRepository(
            accessGuard = LegacyBackendAccessGuard(LegacyActiveAccountBinding(), LegacySelectionStore()),
            chatListRepository = FakeChatListRepository(listOf(chat(77, "Cached later", "preview"))),
            chatCache = ChatCache(),
        )

        val dialog = repository.getDialogs("default").single()

        assertEquals(77L, dialog.peerId)
        assertEquals(DialogPeerType.UNKNOWN, dialog.peerType)
        assertFalse(dialog.isPeerResolved)
    }

    @Test
    fun `blocks dialog reads for non-legacy account assignment`() {
        val selection = LegacySelectionStore(TelegramBackendKind.KOTLIN_MTPROTO)
        val repository = LegacyDialogSnapshotRepository(
            accessGuard = LegacyBackendAccessGuard(LegacyActiveAccountBinding(), selection),
            chatListRepository = FakeChatListRepository(emptyList()),
            chatCache = ChatCache(),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.getDialogs("default") }
        }
    }

    private fun chat(
        id: Long,
        title: String,
        text: String,
        contentType: String = "text",
        lastMessageId: Long = id,
    ) = ChatModel(
        id = id,
        title = title,
        unreadCount = 0,
        lastMessageText = text,
        lastMessageDate = 100,
        lastMessageId = lastMessageId,
        lastMessageContentType = contentType,
    )

    private class FakeChatListRepository(
        chats: List<ChatModel>,
    ) : ChatListRepository {
        override val chatListFlow: StateFlow<List<ChatModel>> = MutableStateFlow(chats)
        override val isLoadingFlow: StateFlow<Boolean> = MutableStateFlow(false)
        override val connectionStateFlow: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.Connected)
        override fun loadNextChunk(limit: Int) = Unit
        override fun selectFolder(folderId: Int) = Unit
        override fun refresh() = Unit
        override fun refreshOnResume() = Unit
        override suspend fun getChatById(chatId: Long): ChatModel? = null
        override suspend fun isChatArchived(chatId: Long): Boolean? = null
        override fun retryConnection() = Unit
    }

    private class LegacySelectionStore(
        private var backend: TelegramBackendKind = TelegramBackendKind.LEGACY,
    ) : TelegramBackendSelectionStore {
        override suspend fun get(accountId: String) = backend
        override fun observe(accountId: String): Flow<TelegramBackendKind> = error("not used")
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { this.backend = backend }
        override suspend fun reset(accountId: String) { backend = TelegramBackendKind.LEGACY }
    }
}

private object TdApiFixtures {
    fun privateChat(chatId: Long, userId: Long) = org.drinkless.tdlib.TdApi.Chat().apply {
        id = chatId
        type = org.drinkless.tdlib.TdApi.ChatTypePrivate(userId)
    }

    fun channelChat(chatId: Long, channelId: Long) = org.drinkless.tdlib.TdApi.Chat().apply {
        id = chatId
        type = org.drinkless.tdlib.TdApi.ChatTypeSupergroup(channelId, true)
    }
}
