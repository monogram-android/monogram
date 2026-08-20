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
    fun `clears histories through the owned repository then refreshes`() = runTest {
        val source = FakeDialogRepository(emptyList())
        val clears = RecordingClearHistoryRepository()
        val repository = MtProtoDialogChatListRepository(
            dialogRepository = source,
            readHistoryRepository = RecordingReadHistoryRepository(),
            scope = backgroundScope,
            clearHistoryRepository = clears,
        )
        runCurrent()

        repository.clearChatHistory(42L, revoke = true)
        runCurrent()

        assertEquals(listOf(setOf(42L) to true), clears.requests)
        assertEquals(2, source.calls)
    }

    @Test
    fun `deletes private dialogs through the owned repository then refreshes`() = runTest {
        val source = FakeDialogRepository(emptyList())
        val deletes = RecordingDeletePrivateDialogRepository()
        val repository = MtProtoDialogChatListRepository(
            dialogRepository = source,
            readHistoryRepository = RecordingReadHistoryRepository(),
            scope = backgroundScope,
            deletePrivateDialogRepository = deletes,
        )
        runCurrent()

        repository.deleteChats(setOf(42L))
        runCurrent()

        assertEquals(listOf(setOf(42L)), deletes.requests)
        assertEquals(2, source.calls)
    }

    @Test
    fun `leaves dialogs through the owned repository then refreshes`() = runTest {
        val source = FakeDialogRepository(emptyList())
        val leaves = RecordingLeaveChatRepository()
        val repository = MtProtoDialogChatListRepository(
            dialogRepository = source,
            readHistoryRepository = RecordingReadHistoryRepository(),
            scope = backgroundScope,
            leaveChatRepository = leaves,
        )
        runCurrent()

        repository.leaveChat(42L)
        runCurrent()

        assertEquals(listOf(setOf(42L)), leaves.requests)
        assertEquals(2, source.calls)
    }

    @Test
    fun `mutes dialogs through the owned mute repository then refreshes`() = runTest {
        val source = FakeDialogRepository(emptyList())
        val mutes = RecordingMuteRepository()
        val repository = MtProtoDialogChatListRepository(
            dialogRepository = source,
            readHistoryRepository = RecordingReadHistoryRepository(),
            scope = backgroundScope,
            muteRepository = mutes,
        )
        runCurrent()

        repository.toggleMuteChats(setOf(42L), mute = true)
        runCurrent()

        assertEquals(listOf(setOf(42L) to true), mutes.requests)
        assertEquals(2, source.calls)
    }

    @Test
    fun `pins dialogs through the owned pin repository then refreshes`() = runTest {
        val source = FakeDialogRepository(emptyList())
        val pins = RecordingDialogPinRepository()
        val repository = MtProtoDialogChatListRepository(
            dialogRepository = source,
            readHistoryRepository = RecordingReadHistoryRepository(),
            scope = backgroundScope,
            dialogPinRepository = pins,
        )
        runCurrent()

        repository.togglePinChats(setOf(42L), pin = true, folderId = -1)
        runCurrent()

        assertEquals(listOf(setOf(42L) to true), pins.requests)
        assertEquals(2, source.calls)
    }

    @Test
    fun `archives chats through the owned archive repository then refreshes`() = runTest {
        val source = FakeDialogRepository(emptyList())
        val archive = RecordingArchiveRepository()
        val repository = MtProtoDialogChatListRepository(
            dialogRepository = source,
            readHistoryRepository = RecordingReadHistoryRepository(),
            scope = backgroundScope,
            archiveRepository = archive,
        )
        runCurrent()

        repository.toggleArchiveChats(setOf(42L), archive = true)
        runCurrent()

        assertEquals(listOf(setOf(42L) to true), archive.requests)
        assertEquals(2, source.calls)
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

    private class RecordingDeletePrivateDialogRepository : MtProtoDeletePrivateDialogRepository {
        val requests = mutableListOf<Set<Long>>()

        override suspend fun delete(chatIds: Set<Long>) {
            requests += chatIds
        }
    }

    private class RecordingClearHistoryRepository : MtProtoClearHistoryRepository {
        val requests = mutableListOf<Pair<Set<Long>, Boolean>>()

        override suspend fun clear(chatIds: Set<Long>, revoke: Boolean) {
            requests += chatIds to revoke
        }
    }

    private class RecordingLeaveChatRepository : MtProtoLeaveChatRepository {
        val requests = mutableListOf<Set<Long>>()

        override suspend fun leave(chatIds: Set<Long>) {
            requests += chatIds
        }
    }

    private class RecordingMuteRepository : MtProtoMuteRepository {
        val requests = mutableListOf<Pair<Set<Long>, Boolean>>()

        override suspend fun setMuted(chatIds: Set<Long>, muted: Boolean) {
            requests += chatIds to muted
        }
    }

    private class RecordingDialogPinRepository : MtProtoDialogPinRepository {
        val requests = mutableListOf<Pair<Set<Long>, Boolean>>()

        override suspend fun setPinned(chatIds: Set<Long>, pinned: Boolean) {
            requests += chatIds to pinned
        }
    }

    private class RecordingArchiveRepository : MtProtoArchiveRepository {
        val requests = mutableListOf<Pair<Set<Long>, Boolean>>()

        override suspend fun setArchived(chatIds: Set<Long>, archived: Boolean) {
            requests += chatIds to archived
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
        var calls = 0

        override suspend fun getDialogs(accountId: String): List<DialogSnapshotModel> {
            calls++
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
