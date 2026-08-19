package org.monogram.data.mtproto

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.models.DialogMessagePreviewModel
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.MtProtoReadHistoryRepository

@OptIn(ExperimentalCoroutinesApi::class)
class MtProtoDialogChatListRepositoryTest {
    @Test
    fun `refresh maps readable dialog projections into all chats`() = runTest {
        val repository = MtProtoDialogChatListRepository(
            dialogRepository = FakeDialogRepository(
                listOf(
                    dialog(DialogPeerType.CHANNEL, 3_768_707_135L, date = 10, title = "Channel"),
                    dialog(DialogPeerType.PRIVATE, 42L, date = 20, title = "Private"),
                    dialog(DialogPeerType.UNKNOWN, 9L, date = 30, title = "Unknown"),
                    dialog(DialogPeerType.BASIC_GROUP, 10L, date = 40, title = "Deleted", deleted = true),
                ),
            ),
            readHistoryRepository = RecordingReadHistoryRepository(),
            scope = backgroundScope,
        )

        runCurrent()

        assertEquals(listOf(42L, -1_003_768_707_135L), repository.chatListFlow.value.map { it.id })
        assertEquals("Private", repository.chatListFlow.value.first().title)
        assertEquals(true, repository.chatListFlow.value[1].isChannel)
        assertEquals(listOf(-1), repository.foldersFlow.value.map { it.id })
        assertEquals(
            listOf(42L, -1_003_768_707_135L),
            repository.folderChatsFlow.first().chats.map { it.id },
        )
    }

    @Test
    fun `failed refresh preserves the current projection and allows retry`() = runTest {
        val source = FakeDialogRepository(listOf(dialog(DialogPeerType.PRIVATE, 42L, date = 1, title = "One")))
        val repository = MtProtoDialogChatListRepository(source, RecordingReadHistoryRepository(), backgroundScope)
        runCurrent()
        source.failure = IllegalStateException("offline")

        repository.refresh()
        runCurrent()

        assertEquals(listOf(42L), repository.chatListFlow.value.map { it.id })
        assertEquals("Connecting", repository.connectionStateFlow.value::class.simpleName)
        assertEquals(false, repository.isLoadingFlow.value)
    }

    @Test
    fun `marks projected chats read through owned receipt repository`() = runTest {
        val receipts = RecordingReadHistoryRepository()
        val repository = MtProtoDialogChatListRepository(
            FakeDialogRepository(listOf(dialog(DialogPeerType.PRIVATE, 42L, date = 7, title = "Peer"))),
            receipts,
            backgroundScope,
        )
        runCurrent()

        repository.markChatsAsRead(setOf(42L))
        runCurrent()

        assertEquals(listOf(Triple(42L, DialogPeerType.PRIVATE, 7L)), receipts.requests)
    }

    @Test
    fun `mark unread is rejected rather than delegated to TDLib`() = runTest {
        val repository = MtProtoDialogChatListRepository(
            FakeDialogRepository(emptyList()),
            RecordingReadHistoryRepository(),
            backgroundScope,
        )
        runCurrent()

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.toggleReadChats(emptySet(), markAsUnread = true) }
        }
    }

    @Test
    fun `custom folders are rejected rather than delegated to TDLib`() = runTest {
        val repository = MtProtoDialogChatListRepository(
            FakeDialogRepository(emptyList()),
            RecordingReadHistoryRepository(),
            backgroundScope,
        )
        runCurrent()

        assertThrows(IllegalArgumentException::class.java) {
            repository.selectFolder(0)
        }
    }

    private class RecordingReadHistoryRepository : MtProtoReadHistoryRepository {
        val requests = mutableListOf<Triple<Long, DialogPeerType, Long>>()

        override suspend fun markRead(chatId: Long, peerType: DialogPeerType, maxMessageId: Long) {
            requests += Triple(chatId, peerType, maxMessageId)
        }
    }

    private class FakeDialogRepository(
        private val dialogs: List<DialogSnapshotModel>,
    ) : DialogSnapshotRepository {
        var failure: Throwable? = null

        override suspend fun getDialogs(accountId: String): List<DialogSnapshotModel> {
            failure?.let { throw it }
            return dialogs
        }
    }

    private companion object {
        fun dialog(
            peerType: DialogPeerType,
            peerId: Long,
            date: Int,
            title: String,
            deleted: Boolean = false,
        ) = DialogSnapshotModel(
            peerId = peerId,
            peerType = peerType,
            title = title,
            username = null,
            isPeerResolved = true,
            isPeerDeleted = deleted,
            isPeerForbidden = false,
            latestMessage = DialogMessagePreviewModel(
                messageId = date.toLong(),
                senderId = null,
                date = date,
                text = null,
                isService = false,
                isDeleted = false,
                isOutgoing = false,
                hasMedia = false,
            ),
        )
    }
}
