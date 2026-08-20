package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.dao.MtProtoChatProjectionDao
import org.monogram.data.db.dao.MtProtoMessageProjectionDao
import org.monogram.data.db.dao.MtProtoUserProjectionDao
import org.monogram.data.db.model.MtProtoChatProjectionEntity
import org.monogram.data.db.model.MtProtoMessageProjectionEntity
import org.monogram.data.db.model.MtProtoUserProjectionEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.DialogPeer_2011bde660
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDialogPinned
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDialogUnreadMark

class MtProtoRoomDialogStoreTest {
    private val scope = MtProtoAuthKeyScope("account-1", MtProtoEnvironment.TEST, 4)

    @Test
    fun `derives ordered dialogs from latest peer messages and resolves display metadata`() = runBlocking {
        val messages = listOf(
            message(MtProtoMessagePeerType.USER, 10, 1, date = 100, text = "user latest"),
            message(MtProtoMessagePeerType.USER, 10, 2, date = 50, text = "user older"),
            message(MtProtoMessagePeerType.GROUP, 20, 3, date = 200, text = "group latest"),
            message(MtProtoMessagePeerType.CHANNEL, 30, 4, date = 150, text = "unresolved"),
        )
        val store = MtProtoRoomDialogStore(
            messageDao = FakeMessageDao(messages),
            userDao = FakeUserDao(listOf(user(10, "Alice", "Doe"), user(99, "Not", "A dialog"))),
            chatDao = FakeChatDao(listOf(chat(20, "Group"))),
            dialogDao = FakeDialogDao(),
        )

        val dialogs = store.getAll(scope)

        assertEquals(listOf(20L, 30L, 10L), dialogs.map { it.peerId })
        assertEquals("Group", dialogs[0].title)
        assertEquals(MtProtoDialogPeerKind.BASIC_GROUP, dialogs[0].peerKind)
        assertFalse(dialogs[1].isPeerResolved)
        assertEquals(MtProtoDialogPeerKind.UNKNOWN, dialogs[1].peerKind)
        assertEquals("Alice Doe", dialogs[2].title)
        assertEquals(1, dialogs[2].latestMessage.messageId)
        assertTrue(dialogs.none { it.peerId == 99L })
    }

    @Test
    fun `applies live pin and unread metadata to a dialog`() = runBlocking {
        val dao = FakeDialogDao()
        val store = MtProtoRoomDialogStore(
            messageDao = FakeMessageDao(emptyList()),
            userDao = FakeUserDao(emptyList()),
            chatDao = FakeChatDao(emptyList()),
            dialogDao = dao,
        )
        val peer = DialogPeer_2011bde660(PeerUser(42))

        store.updatePinned(scope, UpdateDialogPinned(true, 3, peer))
        store.updateUnreadMark(scope, UpdateDialogUnreadMark(true, peer, null))

        assertEquals("USER", dao.pinnedPeerType)
        assertEquals(42L, dao.pinnedPeerId)
        assertTrue(dao.pinned)
        assertEquals(3, dao.folderId)
        assertTrue(dao.unread)
    }

    @Test
    fun `retains persisted dialog without a message preview`() = runBlocking {
        val store = MtProtoRoomDialogStore(
            messageDao = FakeMessageDao(emptyList()),
            userDao = FakeUserDao(listOf(user(42, "Empty", "Dialog"))),
            chatDao = FakeChatDao(emptyList()),
            dialogDao = FakeDialogDao(
                listOf(
                    org.monogram.data.db.model.MtProtoDialogProjectionEntity(
                        accountSlot = "account-1",
                        environment = "test",
                        dcId = 4,
                        peerType = MtProtoMessagePeerType.USER.name,
                        peerId = 42,
                        pinned = false,
                        muted = false,
                        unreadMark = false,
                        topMessageId = 0,
                        unreadCount = 0,
                        unreadMentionsCount = 0,
                        unreadReactionsCount = 0,
                        folderId = null,
                        updatedAt = 1,
                    ),
                ),
            ),
        )

        val dialog = store.getAll(scope).single()

        assertEquals(42L, dialog.peerId)
        assertEquals("Empty Dialog", dialog.title)
        assertEquals(0, dialog.latestMessage.messageId)
        assertTrue(dialog.isPeerResolved)
    }

    private fun message(
        peerType: MtProtoMessagePeerType,
        peerId: Long,
        messageId: Int,
        date: Int,
        text: String,
    ) = MtProtoMessageProjectionEntity(
        accountSlot = "account-1",
        environment = "test",
        dcId = 4,
        peerType = peerType.name,
        peerId = peerId,
        messageId = messageId,
        senderType = null,
        senderId = null,
        date = date,
        text = text,
        isService = false,
        isDeleted = false,
        isOutgoing = false,
        isMentioned = false,
        isMediaUnread = false,
        isSilent = false,
        isPinned = false,
        editDate = null,
        groupedId = null,
        hasMedia = false,
        updatedAt = 1,
    )

    private fun user(id: Long, firstName: String, lastName: String) = MtProtoUserProjectionEntity(
        accountSlot = "account-1",
        environment = "test",
        dcId = 4,
        userId = id,
        accessHash = null,
        firstName = firstName,
        lastName = lastName,
        username = null,
        phone = null,
        isSelf = false,
        isContact = false,
        isMutualContact = false,
        isDeleted = false,
        isBot = false,
        isVerified = false,
        isRestricted = false,
        isScam = false,
        isFake = false,
        isPremium = false,
        isMin = false,
        updatedAt = 1,
    )

    private fun chat(id: Long, title: String) = MtProtoChatProjectionEntity(
        accountSlot = "account-1",
        environment = "test",
        dcId = 4,
        chatId = id,
        type = MtProtoChatType.BASIC_GROUP.name,
        accessHash = null,
        title = title,
        username = null,
        participantsCount = null,
        isDeleted = false,
        isForbidden = false,
        isLeft = false,
        isDeactivated = false,
        isBroadcast = false,
        isMegagroup = false,
        isVerified = false,
        isRestricted = false,
        isScam = false,
        isFake = false,
        isForum = false,
        isMin = false,
        updatedAt = 1,
    )

    private class FakeMessageDao(private val messages: List<MtProtoMessageProjectionEntity>) : MtProtoMessageProjectionDao {
        override suspend fun get(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, messageId: Int) = messages.firstOrNull { it.peerType == peerType && it.peerId == peerId && it.messageId == messageId }
        override suspend fun getAll(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long) = messages.filter { it.peerType == peerType && it.peerId == peerId }
        override suspend fun search(accountSlot: String, environment: String, dcId: Int, query: String, limit: Int, offset: Int) = emptyList<MtProtoMessageProjectionEntity>()
        override suspend fun getPage(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, beforeDate: Int?, beforeMessageId: Int?, limit: Int) = getAll(accountSlot, environment, dcId, peerType, peerId).take(limit)
        override suspend fun getLatestByPeer(accountSlot: String, environment: String, dcId: Int) = messages
            .groupBy { it.peerType to it.peerId }
            .values
            .map { it.maxWith(compareBy<MtProtoMessageProjectionEntity> { message -> message.date }.thenBy { message -> message.messageId }) }
            .sortedWith(compareByDescending<MtProtoMessageProjectionEntity> { it.date }.thenByDescending { it.messageId })
        override suspend fun upsert(entity: MtProtoMessageProjectionEntity) = Unit
        override suspend fun markDeletedNonChannel(accountSlot: String, environment: String, dcId: Int, messageIds: List<Int>, updatedAt: Long) = Unit
        override suspend fun markDeletedChannel(accountSlot: String, environment: String, dcId: Int, peerId: Long, messageIds: List<Int>, updatedAt: Long) = Unit
        override suspend fun deleteAccount(accountSlot: String, environment: String) = Unit
    }

    private class FakeUserDao(private val users: List<MtProtoUserProjectionEntity>) : MtProtoUserProjectionDao {
        override suspend fun get(accountSlot: String, environment: String, dcId: Int, userId: Long) = users.firstOrNull { it.userId == userId }
        override suspend fun getSelf(accountSlot: String, environment: String, dcId: Int) = users.firstOrNull { it.isSelf }
        override suspend fun getAll(accountSlot: String, environment: String, dcId: Int) = users
        override suspend fun upsert(entity: MtProtoUserProjectionEntity) = Unit
        override suspend fun deleteAccount(accountSlot: String, environment: String) = Unit
    }

    private class FakeDialogDao(
        private val dialogs: List<org.monogram.data.db.model.MtProtoDialogProjectionEntity> = emptyList(),
    ) : org.monogram.data.db.dao.MtProtoDialogProjectionDao {
        var pinnedPeerType: String? = null
        var pinnedPeerId: Long? = null
        var pinned = false
        var folderId: Int? = null
        var unread = false
        var muted = false
        override suspend fun getAll(accountSlot: String, environment: String, dcId: Int) = dialogs
        override suspend fun upsert(entity: org.monogram.data.db.model.MtProtoDialogProjectionEntity) = Unit
        override suspend fun upsertAll(entities: List<org.monogram.data.db.model.MtProtoDialogProjectionEntity>) = Unit
        override suspend fun updateTopMessage(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, messageId: Int, updatedAt: Long) = Unit
        override suspend fun updatePinned(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, pinned: Boolean, folderId: Int?, updatedAt: Long) {
            pinnedPeerType = peerType
            pinnedPeerId = peerId
            this.pinned = pinned
            this.folderId = folderId
        }
        override suspend fun updateMuted(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, muted: Boolean, updatedAt: Long) {
            this.muted = muted
        }
        override suspend fun updateUnreadMark(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, unread: Boolean, updatedAt: Long) {
            this.unread = unread
        }
        override suspend fun deleteAccount(accountSlot: String, environment: String) = Unit
    }

    private class FakeChatDao(private val chats: List<MtProtoChatProjectionEntity>) : MtProtoChatProjectionDao {
        override suspend fun get(accountSlot: String, environment: String, dcId: Int, chatId: Long) = chats.firstOrNull { it.chatId == chatId }
        override suspend fun getAll(accountSlot: String, environment: String, dcId: Int) = chats
        override suspend fun upsert(entity: MtProtoChatProjectionEntity) = Unit
        override suspend fun deleteAccount(accountSlot: String, environment: String) = Unit
    }
}
