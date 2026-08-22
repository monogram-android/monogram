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
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig

class MtProtoDeletePrivateDialogRepositoryTest {
    @Test
    fun `deletes projected private dialog with revoke then removes its projection`() = runBlocking {
        val messages = RecordingMessages()
        val dialogs = RecordingDialogStore()
        val repository = MtProtoDeletePrivateDialogRepositoryImpl(
            dialogs = FakeDialogs(listOf(dialog(DialogPeerType.PRIVATE, 42L))),
            messages = messages,
            dialogStore = dialogs,
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
        )

        repository.delete(setOf(42L))

        assertEquals(listOf(Triple(42L, DialogPeerType.PRIVATE, true)), messages.calls)
        assertEquals(listOf(MtProtoMessagePeerType.USER to 42L), dialogs.deleted)
    }

    @Test
    fun `rejects a batch containing a non-private dialog before deleting any dialog`() {
        val messages = RecordingMessages()
        val repository = MtProtoDeletePrivateDialogRepositoryImpl(
            dialogs = FakeDialogs(listOf(dialog(DialogPeerType.PRIVATE, 42L), dialog(DialogPeerType.BASIC_GROUP, 7L))),
            messages = messages,
            dialogStore = RecordingDialogStore(),
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.delete(setOf(42L, -7L)) }
        }
        assertEquals(emptyList<Triple<Long, DialogPeerType, Boolean>>(), messages.calls)
    }

    private class FakeDialogs(private val values: List<DialogSnapshotModel>) : DialogSnapshotRepository {
        override suspend fun getDialogs(accountId: String) = values
    }

    private class RecordingMessages : MtProtoTextMessageRepository {
        val calls = mutableListOf<Triple<Long, DialogPeerType, Boolean>>()
        override suspend fun clearHistory(chatId: Long, peerType: DialogPeerType, revoke: Boolean) {
            calls += Triple(chatId, peerType, revoke)
        }
        override suspend fun sendText(chatId: Long, peerType: DialogPeerType, text: String, silent: Boolean, scheduleDate: Int?, disableLinkPreview: Boolean) = Unit
        override suspend fun sendTyping(chatId: Long, peerType: DialogPeerType, threadId: Long?) = Unit
        override suspend fun editText(chatId: Long, peerType: DialogPeerType, messageId: Long, text: String) = Unit
        override suspend fun setEmojiReaction(chatId: Long, peerType: DialogPeerType, messageId: Long, emoji: String?) = Unit
        override suspend fun setPinned(chatId: Long, peerType: DialogPeerType, messageId: Long, pinned: Boolean) = Unit
        override suspend fun forwardToSelf(chatId: Long, peerType: DialogPeerType, messageId: Long) = Unit
        override suspend fun forwardMessages(request: org.monogram.domain.repository.ForwardRequest) = Unit
        override suspend fun sendScheduledNow(chatId: Long, peerType: DialogPeerType, messageId: Long) = Unit
        override suspend fun markMentionsRead(chatId: Long, peerType: DialogPeerType) = Unit
        override suspend fun markReactionsRead(chatId: Long, peerType: DialogPeerType) = Unit
    }

    private class RecordingDialogStore : MtProtoDialogStore by NoOpMtProtoDialogStore {
        val deleted = mutableListOf<Pair<MtProtoMessagePeerType, Long>>()
        override suspend fun delete(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long) {
            deleted += peerType to peerId
        }
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

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(4, "dc", 443),
        handshake = MtProtoHandshakeConfig(4, listOf("test-key")),
        cloud = CloudLayer223ConnectionConfig(12345, "device", "system", "app", "en"),
    )
}
