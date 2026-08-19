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
        val repository = MtProtoDialogChatListRepository(source, backgroundScope)
        runCurrent()
        source.failure = IllegalStateException("offline")

        repository.refresh()
        runCurrent()

        assertEquals(listOf(42L), repository.chatListFlow.value.map { it.id })
        assertEquals("Connecting", repository.connectionStateFlow.value::class.simpleName)
        assertEquals(false, repository.isLoadingFlow.value)
    }

    @Test
    fun `custom folders are rejected rather than delegated to TDLib`() = runTest {
        val repository = MtProtoDialogChatListRepository(FakeDialogRepository(emptyList()), backgroundScope)
        runCurrent()

        assertThrows(IllegalArgumentException::class.java) {
            repository.selectFolder(0)
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
