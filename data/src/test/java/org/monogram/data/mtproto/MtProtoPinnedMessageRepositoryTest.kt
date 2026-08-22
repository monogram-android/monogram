package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.models.DialogMessagePreviewModel
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.MtProtoTextMessageRepository

class MtProtoPinnedMessageRepositoryTest {
    @Test
    fun `uses persisted peer type when pinning projected dialog`() = runBlocking {
        val messages = RecordingMessages()
        val repository = MtProtoPinnedMessageRepositoryImpl(
            dialogs = FakeDialogs(listOf(dialog(DialogPeerType.SUPERGROUP, 42L))),
            messages = messages,
        )

        repository.setPinned(-1_000_000_000_042L, 7L, pinned = true)

        assertEquals(listOf(Triple(-1_000_000_000_042L, DialogPeerType.SUPERGROUP, 7L)), messages.pinned)
    }

    @Test
    fun `rejects pinning a dialog absent from the MTProto projection`() {
        val repository = MtProtoPinnedMessageRepositoryImpl(FakeDialogs(emptyList()), RecordingMessages())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setPinned(42L, 7L, pinned = true) }
        }
    }

    private class FakeDialogs(private val values: List<DialogSnapshotModel>) : DialogSnapshotRepository {
        override suspend fun getDialogs(accountId: String) = values
    }

    private class RecordingMessages : MtProtoTextMessageRepository {
        val pinned = mutableListOf<Triple<Long, DialogPeerType, Long>>()
        override suspend fun setPinned(chatId: Long, peerType: DialogPeerType, messageId: Long, pinned: Boolean) {
            this.pinned += Triple(chatId, peerType, messageId)
        }
        override suspend fun sendText(chatId: Long, peerType: DialogPeerType, text: String, silent: Boolean, scheduleDate: Int?, disableLinkPreview: Boolean) = Unit
        override suspend fun sendTyping(chatId: Long, peerType: DialogPeerType, threadId: Long?) = Unit
        override suspend fun editText(chatId: Long, peerType: DialogPeerType, messageId: Long, text: String) = Unit
        override suspend fun setEmojiReaction(chatId: Long, peerType: DialogPeerType, messageId: Long, emoji: String?) = Unit
        override suspend fun forwardToSelf(chatId: Long, peerType: DialogPeerType, messageId: Long) = Unit
        override suspend fun forwardMessages(request: org.monogram.domain.repository.ForwardRequest) = Unit
        override suspend fun sendScheduledNow(chatId: Long, peerType: DialogPeerType, messageId: Long) = Unit
        override suspend fun clearHistory(chatId: Long, peerType: DialogPeerType, revoke: Boolean) = Unit
        override suspend fun markMentionsRead(chatId: Long, peerType: DialogPeerType) = Unit
        override suspend fun markReactionsRead(chatId: Long, peerType: DialogPeerType) = Unit
    }

    private fun dialog(type: DialogPeerType, id: Long) = DialogSnapshotModel(
        peerId = id,
        peerType = type,
        title = null,
        username = null,
        isPeerResolved = true,
        isPeerDeleted = false,
        isPeerForbidden = false,
        latestMessage = DialogMessagePreviewModel(1L, null, 1, null, false, false, false, false),
    )
}
