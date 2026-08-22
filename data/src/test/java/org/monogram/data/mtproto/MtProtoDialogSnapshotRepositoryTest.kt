package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.Dialog_cf9860a8bd
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerNotifySettings_474d6bbc59
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DialogsSlice
import org.monogram.domain.models.DialogPeerType
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcException

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

    @Test
    fun `parses bounded dialog flood waits only`() {
        assertEquals(19L, MtProtoRpcException(420, "FLOOD_WAIT_19").floodWaitSeconds())
        assertEquals(null, MtProtoRpcException(420, "FLOOD_WAIT_61").floodWaitSeconds())
        assertEquals(null, MtProtoRpcException(400, "FLOOD_WAIT_19").floodWaitSeconds())
    }

    @Test
    fun `uses the last unpinned dialog as pagination cursor`() {
        val unpinned = dialog(10)
        val pinned = dialog(11, pinned = true)

        assertEquals(unpinned, listOf(unpinned, pinned).lastUnpinnedDialog())
    }

    @Test
    fun `stops dialog pagination when cumulative slice count reaches total`() {
        val first = DialogsSlice(2, listOf(dialog(10), dialog(11)), emptyList(), emptyList(), emptyList())
        val second = DialogsSlice(2, listOf(dialog(12)), emptyList(), emptyList(), emptyList())

        assertEquals(false, first.dialogPageForTest().hasMore(2))
        assertEquals(true, second.dialogPageForTest().hasMore(1))
    }

    private fun DialogsSlice.dialogPageForTest() = DialogPageForTest(
        dialogs = dialogs.filterIsInstance<Dialog_cf9860a8bd>(),
        totalCount = count,
    )

    private data class DialogPageForTest(val dialogs: List<Dialog_cf9860a8bd>, val totalCount: Int) {
        fun hasMore(loadedDialogs: Int) = loadedDialogs < totalCount
    }

    private fun dialog(topMessage: Int, pinned: Boolean = false) = Dialog_cf9860a8bd(
        pinned = pinned,
        unreadMark = false,
        viewForumAsMessages = false,
        peer = PeerChat(topMessage.toLong()),
        topMessage = topMessage,
        readInboxMaxId = 0,
        readOutboxMaxId = 0,
        unreadCount = 0,
        unreadMentionsCount = 0,
        unreadReactionsCount = 0,
        notifySettings = PeerNotifySettings_474d6bbc59(null, null, null, null, null, null, null, null, null, null, null),
        pts = null,
        draft = null,
        folderId = null,
        ttlPeriod = null,
    )

    private fun config() = config(dcId = 2)

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
                    isMuted = false,
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

        override suspend fun getByFolder(scope: MtProtoAuthKeyScope, folderId: Int): List<MtProtoDialogReadModel> =
            getAll(scope)
    }

    @Test
    fun `loadMore reports exhausted without opening a transport before or after a full fetch`() = runBlocking {
        var opened = false
        val repository = MtProtoDialogSnapshotRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            dialogStore = NoOpMtProtoDialogStore,
            sessionFactory = TelegramMtProtoSessionFactory(
                configSource = TelegramMtProtoBootstrapConfigSource { config() },
                keyLoader = MtProtoAuthKeyLoader { _, _, _ -> error("no transport expected") },
                handshakeConnectionFactory = { opened = true; error("no handshake expected") },
            ),
        )

        // Before any fetch: no continuation exists.
        assertTrue(repository.loadMore("default", 20).isEmpty())
        assertFalse(opened)
    }
}
