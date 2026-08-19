package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.MessageHistoryCursorModel
import org.monogram.domain.models.MessageHistorySnapshotRequest
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig

class MtProtoMessageHistorySnapshotRepositoryTest {
    @Test
    fun `maps scoped projection page and continuation cursor`() = runBlocking {
        val store = RecordingMessageStore(
            listOf(message(12, date = 100), message(11, date = 99))
        )
        val repository = MtProtoMessageHistorySnapshotRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config(dcId = 4) },
            messageStore = store,
        )

        val page = repository.getHistory(
            MessageHistorySnapshotRequest(
                accountId = "account-1",
                peerType = DialogPeerType.SUPERGROUP,
                peerId = 7,
                before = MessageHistoryCursorModel(101, 13),
                limit = 2,
            )
        )

        assertEquals(MtProtoAuthKeyScope("account-1", MtProtoEnvironment.PRODUCTION, 4), store.scope)
        assertEquals(MtProtoMessagePeerType.CHANNEL, store.peerType)
        assertEquals(MtProtoMessageHistoryCursor(101, 13), store.before)
        assertEquals(listOf(12L, 11L), page.messages.map { it.messageId })
        assertEquals("message-12", page.messages.first().text)
        assertEquals(MessageHistoryCursorModel(99, 11), page.nextCursor)
    }

    @Test
    fun `omits cursor for a partial page and rejects unknown peer`() = runBlocking {
        val repository = MtProtoMessageHistorySnapshotRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config(dcId = 2) },
            messageStore = RecordingMessageStore(listOf(message(1, date = 1))),
        )

        val page = repository.getHistory(MessageHistorySnapshotRequest("account", DialogPeerType.PRIVATE, 5, limit = 2))

        assertNull(page.nextCursor)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.getHistory(MessageHistorySnapshotRequest("account", DialogPeerType.UNKNOWN, 5))
            }
        }
        Unit
    }

    @Test
    fun `rejects cursor message ids outside MTProto range`() {
        val repository = MtProtoMessageHistorySnapshotRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config(dcId = 2) },
            messageStore = RecordingMessageStore(emptyList()),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.getHistory(
                    MessageHistorySnapshotRequest(
                        accountId = "account",
                        peerType = DialogPeerType.PRIVATE,
                        peerId = 5,
                        before = MessageHistoryCursorModel(10, Int.MAX_VALUE.toLong() + 1),
                    )
                )
            }
        }
    }

    private fun message(id: Int, date: Int) = MtProtoMessageReadModel(
        peerType = MtProtoMessagePeerType.CHANNEL,
        peerId = 7,
        messageId = id,
        senderType = MtProtoMessagePeerType.USER,
        senderId = 3,
        date = date,
        text = "message-$id",
        isService = false,
        isDeleted = false,
        isOutgoing = true,
        isMentioned = true,
        isMediaUnread = false,
        isSilent = true,
        isPinned = false,
        editDate = null,
        groupedId = 10,
        hasMedia = true,
    )

    private fun config(dcId: Int) = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(dcId, "dc", 443),
        handshake = MtProtoHandshakeConfig(dcId, listOf("test-key")),
        cloud = CloudLayer223ConnectionConfig(
            apiId = 12345,
            deviceModel = "device",
            systemVersion = "system",
            applicationVersion = "app",
            systemLanguageCode = "en",
        ),
    )

    private class RecordingMessageStore(
        private val messages: List<MtProtoMessageReadModel>,
    ) : MtProtoMessageProjectionStore by NoOpMtProtoMessageProjectionStore {
        var scope: MtProtoAuthKeyScope? = null
        var peerType: MtProtoMessagePeerType? = null
        var before: MtProtoMessageHistoryCursor? = null

        override suspend fun getPage(
            scope: MtProtoAuthKeyScope,
            peerType: MtProtoMessagePeerType,
            peerId: Long,
            before: MtProtoMessageHistoryCursor?,
            limit: Int,
        ): List<MtProtoMessageReadModel> {
            this.scope = scope
            this.peerType = peerType
            this.before = before
            return messages.take(limit)
        }
    }
}
