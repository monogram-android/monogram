package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.dao.MtProtoChatProjectionDao
import org.monogram.data.db.dao.MtProtoCloudObjectDao
import org.monogram.data.db.model.MtProtoChatProjectionEntity
import org.monogram.data.db.model.MtProtoCloudObjectEntity
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatForbidden
import org.monogram.mtproto.tl.generated.cloud.layer223.Channel
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatPhotoEmpty

class MtProtoRoomChatProjectionStoreTest {
    private val scope = MtProtoAuthKeyScope("account-1", MtProtoEnvironment.TEST, 4)

    @Test
    fun `maps chat empty and forbidden with scoped reads`() = runBlocking {
        val dao = FakeChatProjectionDao()
        val store = MtProtoRoomChatProjectionStore(dao)
        store.upsert(scope, listOf(ChatEmpty(10), ChatForbidden(20, "Private")))
        store.upsert(scope.copy(environment = MtProtoEnvironment.PRODUCTION), listOf(ChatEmpty(30)))

        assertEquals(true, store.get(scope, 10)?.isDeleted)
        assertEquals("Private", store.get(scope, 20)?.title)
        assertEquals(listOf(10L, 20L), store.getAll(scope).map { it.chatId })
        assertEquals(listOf(30L), store.getAll(scope.copy(environment = MtProtoEnvironment.PRODUCTION)).map { it.chatId })
    }

    @Test
    fun `backfill projects valid chat and rejects corrupt payload`() = runBlocking {
        val cloudDao = FakeCloudObjectDao(
            listOf(
                cloudEntity(1, CloudTlObjectCodec.encode(ChatEmpty(40))),
                cloudEntity(2, byteArrayOf(1, 2, 3)),
            )
        )
        val store = MtProtoRoomChatProjectionStore(FakeChatProjectionDao(), cloudObjectDao = cloudDao)

        assertEquals(MtProtoChatProjectionBackfillResult(1, 1), store.backfill(scope))
        assertTrue(store.get(scope, 40)?.isDeleted == true)
    }

    @Test
    fun `channel min update preserves confirmed flags and identity`() = runBlocking {
        val store = MtProtoRoomChatProjectionStore(FakeChatProjectionDao(), nowMillis = { 1234L })
        store.upsert(scope, listOf(channel(id = 50, accessHash = 99, title = "Group", verified = true, megagroup = true, signatures = true, signatureProfiles = true, forumTabs = true)))
        store.upsert(scope, listOf(channel(id = 50, title = "Group min", min = true, megagroup = true)))

        val result = store.get(scope, 50)
        assertEquals("Group min", result?.title)
        assertEquals(99L, result?.accessHash)
        assertTrue(result?.isVerified == true)
        assertTrue(result?.signaturesEnabled == true)
        assertTrue(result?.signatureProfilesEnabled == true)
        assertTrue(result?.forumTabs == true)
        assertTrue(result?.isMin == true)
    }

    private fun channel(
        id: Long,
        accessHash: Long? = null,
        title: String,
        verified: Boolean = false,
        megagroup: Boolean = false,
        min: Boolean = false,
        signatures: Boolean = false,
        signatureProfiles: Boolean = false,
        forumTabs: Boolean = false,
    ) = Channel(
        creator = false,
        left = false,
        broadcast = !megagroup,
        verified = verified,
        megagroup = megagroup,
        restricted = false,
        signatures = signatures,
        min = min,
        scam = false,
        hasLink = false,
        hasGeo = false,
        slowmodeEnabled = false,
        callActive = false,
        callNotEmpty = false,
        fake = false,
        gigagroup = false,
        noforwards = false,
        joinToSend = false,
        joinRequest = false,
        forum = false,
        storiesHidden = false,
        storiesHiddenMin = false,
        storiesUnavailable = false,
        signatureProfiles = signatureProfiles,
        autotranslation = false,
        broadcastMessagesAllowed = false,
        monoforum = false,
        forumTabs = forumTabs,
        id = id,
        accessHash = accessHash,
        title = title,
        username = null,
        photo = ChatPhotoEmpty,
        date = 0,
        restrictionReason = null,
        adminRights = null,
        bannedRights = null,
        defaultBannedRights = null,
        participantsCount = null,
        usernames = null,
        storiesMaxId = null,
        color = null,
        profileColor = null,
        emojiStatus = null,
        level = null,
        subscriptionUntilDate = null,
        botVerificationIcon = null,
        sendPaidMessagesStars = null,
        linkedMonoforumId = null,
    )

    private fun cloudEntity(id: Long, payload: ByteArray) = MtProtoCloudObjectEntity(
        sequenceId = id,
        accountSlot = "account-1",
        environment = "test",
        dcId = 4,
        objectType = "chat",
        payloadHash = "hash-$id",
        payload = payload,
        createdAt = 0,
    )

    private class FakeChatProjectionDao : MtProtoChatProjectionDao {
        private val entities = mutableListOf<MtProtoChatProjectionEntity>()

        override suspend fun get(accountSlot: String, environment: String, dcId: Int, chatId: Long) =
            entities.firstOrNull {
                it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId && it.chatId == chatId
            }

        override suspend fun getAll(accountSlot: String, environment: String, dcId: Int) = entities.filter {
            it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId
        }.sortedBy { it.chatId }

        override suspend fun upsert(entity: MtProtoChatProjectionEntity) {
            entities.removeAll {
                it.accountSlot == entity.accountSlot && it.environment == entity.environment &&
                    it.dcId == entity.dcId && it.chatId == entity.chatId
            }
            entities += entity
        }

        override suspend fun deleteAccount(accountSlot: String, environment: String) {
            entities.removeAll { it.accountSlot == accountSlot && it.environment == environment }
        }
    }

    private class FakeCloudObjectDao(
        private val entities: List<MtProtoCloudObjectEntity>,
    ) : MtProtoCloudObjectDao {
        override suspend fun insertAll(objects: List<MtProtoCloudObjectEntity>) = emptyList<Long>()
        override suspend fun getAll(accountSlot: String, environment: String, dcId: Int) = entities
        override suspend fun getByType(accountSlot: String, environment: String, dcId: Int, objectType: String) =
            entities.filter { it.objectType == objectType }
        override suspend fun deleteAccount(accountSlot: String, environment: String) = Unit
    }
}
