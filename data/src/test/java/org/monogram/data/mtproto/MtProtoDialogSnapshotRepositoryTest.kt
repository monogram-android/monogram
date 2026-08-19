package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.models.DialogPeerType
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig

class MtProtoDialogSnapshotRepositoryTest {
    @Test
    fun `maps internal dialog projection into backend neutral domain snapshot`() = runBlocking {
        val store = RecordingDialogStore()
        val repository = MtProtoDialogSnapshotRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config(dcId = 4) },
            dialogStore = store,
        )

        val dialogs = repository.getDialogs("account-1")

        assertEquals(MtProtoAuthKeyScope("account-1", MtProtoEnvironment.PRODUCTION, 4), store.scope)
        assertEquals(1, dialogs.size)
        assertEquals(DialogPeerType.SUPERGROUP, dialogs.single().peerType)
        assertEquals("Group", dialogs.single().title)
        assertEquals("hello", dialogs.single().latestMessage.text)
        assertEquals(2, dialogs.single().unreadCount)
        assertEquals(1, dialogs.single().unreadMentionsCount)
        assertEquals(true, dialogs.single().isPinned)
    }

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

    private class RecordingDialogStore : MtProtoDialogStore {
        var scope: MtProtoAuthKeyScope? = null

        override suspend fun getAll(scope: MtProtoAuthKeyScope): List<MtProtoDialogReadModel> {
            this.scope = scope
            return listOf(
                MtProtoDialogReadModel(
                    peerType = MtProtoMessagePeerType.CHANNEL,
                    peerKind = MtProtoDialogPeerKind.SUPERGROUP,
                    peerId = 10,
                    title = "Group",
                    username = "group",
                    isPeerResolved = true,
                    isPeerDeleted = false,
                    isPeerForbidden = false,
                    unreadCount = 2,
                    unreadMentionsCount = 1,
                    unreadReactionsCount = 0,
                    isPinned = true,
                    latestMessage = MtProtoDialogMessagePreview(
                        messageId = 20,
                        senderType = MtProtoMessagePeerType.USER,
                        senderId = 30,
                        date = 100,
                        text = "hello",
                        isService = false,
                        isDeleted = false,
                        isOutgoing = false,
                        hasMedia = false,
                    ),
                )
            )
        }
    }
}
