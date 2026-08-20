package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.dao.MtProtoCloudObjectDao
import org.monogram.data.db.dao.MtProtoMessageProjectionDao
import org.monogram.data.db.model.MtProtoCloudObjectEntity
import org.monogram.data.db.model.MtProtoMessageProjectionEntity
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaDocument
import org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteChannelMessages
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShort
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShortMessage
import org.monogram.mtproto.updates.MtProtoUpdateCursor
import org.monogram.mtproto.updates.MtProtoUpdateDifferenceBatch

class MtProtoRoomMessageProjectionStoreTest {
    private val scope = MtProtoAuthKeyScope("account-1", MtProtoEnvironment.TEST, 4)

    @Test
    fun `projects short private message and applies non-channel deletion`() = runBlocking {
        val store = MtProtoRoomMessageProjectionStore(FakeMessageProjectionDao(), nowMillis = { 1234L })
        store.stageLive(
            scope,
            shortMessage(id = 10, userId = 7, text = "hello"),
        )

        assertEquals("hello", store.get(scope, MtProtoMessagePeerType.USER, 7, 10)?.text)
        store.stageLive(scope, UpdateShort(UpdateDeleteMessages(listOf(10), 2, 1), 101))

        assertTrue(store.get(scope, MtProtoMessagePeerType.USER, 7, 10)?.isDeleted == true)
    }

    @Test
    fun `channel deletion remains peer scoped`() = runBlocking {
        val dao = FakeMessageProjectionDao()
        dao.upsert(entity(MtProtoMessagePeerType.CHANNEL, 50, 20))
        dao.upsert(entity(MtProtoMessagePeerType.CHANNEL, 60, 20))
        val store = MtProtoRoomMessageProjectionStore(dao, nowMillis = { 1234L })

        store.stageDifference(
            scope,
            batch(otherUpdates = listOf(UpdateDeleteChannelMessages(50, listOf(20), 2, 1))),
        )

        assertTrue(store.get(scope, MtProtoMessagePeerType.CHANNEL, 50, 20)?.isDeleted == true)
        assertTrue(store.get(scope, MtProtoMessagePeerType.CHANNEL, 60, 20)?.isDeleted == false)
    }

    @Test
    fun `backfill projects peer-resolved message and rejects corrupt payload`() = runBlocking {
        val cloudDao = FakeCloudObjectDao(
            listOf(
                cloudEntity(1, "message", CloudTlObjectCodec.encode(MessageEmpty(30, PeerChat(5)))),
                cloudEntity(2, "message", CloudTlObjectCodec.encode(MessageEmpty(31, PeerUser(6)))),
                cloudEntity(3, "message", byteArrayOf(1, 2, 3)),
                cloudEntity(4, "live_updates", CloudTlObjectCodec.encode(shortMessage(31, 6, "restored"))),
            )
        )
        val store = MtProtoRoomMessageProjectionStore(FakeMessageProjectionDao(), cloudObjectDao = cloudDao)

        assertEquals(MtProtoMessageProjectionBackfillResult(3, 1), store.backfill(scope))
        assertTrue(store.get(scope, MtProtoMessagePeerType.GROUP, 5, 30)?.isDeleted == true)
        assertEquals("restored", store.get(scope, MtProtoMessagePeerType.USER, 6, 31)?.text)
        assertTrue(store.get(scope, MtProtoMessagePeerType.USER, 6, 31)?.isDeleted == false)
    }

    @Test
    fun `projects document identity with its message`() = runBlocking {
        val locations = RecordingDocumentLocations()
        val store = MtProtoRoomMessageProjectionStore(
            dao = FakeMessageProjectionDao(),
            documentLocations = locations,
        )

        store.stageMessages(scope, listOf(documentMessage()))

        assertEquals(9L, store.get(scope, MtProtoMessagePeerType.USER, 7L, 10)?.documentId)
        assertEquals(9L, locations.document?.id)
        assertEquals(4, locations.document?.dcId)
    }

    @Test
    fun `search returns matching projections in stable paged order`() = runBlocking {
        val dao = FakeMessageProjectionDao()
        dao.upsert(entity(MtProtoMessagePeerType.USER, 7, 13, date = 101).copy(text = "alpha one"))
        dao.upsert(entity(MtProtoMessagePeerType.USER, 7, 12, date = 100).copy(text = "beta"))
        dao.upsert(entity(MtProtoMessagePeerType.GROUP, 8, 11, date = 99).copy(text = "alpha two"))
        val store = MtProtoRoomMessageProjectionStore(dao)

        val page = store.search(scope, "alpha", limit = 1, offset = 1)

        assertEquals(listOf(11), page.map { it.messageId })
        assertEquals(MtProtoMessagePeerType.GROUP, page.single().peerType)
    }

    @Test
    fun `history page uses date and message id cursor without overlap`() = runBlocking {
        val dao = FakeMessageProjectionDao()
        dao.upsert(entity(MtProtoMessagePeerType.USER, 7, 13, date = 101))
        dao.upsert(entity(MtProtoMessagePeerType.USER, 7, 12, date = 100))
        dao.upsert(entity(MtProtoMessagePeerType.USER, 7, 11, date = 100))
        dao.upsert(entity(MtProtoMessagePeerType.USER, 8, 20, date = 200))
        val store = MtProtoRoomMessageProjectionStore(dao)

        val firstPage = store.getPage(scope, MtProtoMessagePeerType.USER, 7, before = null, limit = 2)
        val secondPage = store.getPage(
            scope,
            MtProtoMessagePeerType.USER,
            7,
            before = MtProtoMessageHistoryCursor(firstPage.last().date, firstPage.last().messageId),
            limit = 2,
        )

        assertEquals(listOf(13, 12), firstPage.map { it.messageId })
        assertEquals(listOf(11), secondPage.map { it.messageId })
    }

    private fun documentMessage() = Message_7b7ecf54a3(
        out_ = false,
        mentioned = false,
        mediaUnread = false,
        silent = false,
        post = false,
        fromScheduled = false,
        legacy = false,
        editHide = false,
        pinned = false,
        noforwards = false,
        invertMedia = false,
        offline = false,
        videoProcessingPending = false,
        paidSuggestedPostStars = false,
        paidSuggestedPostTon = false,
        id = 10,
        fromId = null,
        fromBoostsApplied = null,
        fromRank = null,
        peerId = PeerUser(7L),
        savedPeerId = null,
        fwdFrom = null,
        viaBotId = null,
        viaBusinessBotId = null,
        replyTo = null,
        date = 100,
        message = "document",
        media = MessageMediaDocument(
            nopremium = false,
            spoiler = false,
            video = false,
            round = false,
            voice = false,
            document = Document_be725c3b31(
                id = 9L,
                accessHash = 10L,
                fileReference = TlBytes.copyOf(byteArrayOf(1)),
                date = 0,
                mimeType = "application/pdf",
                size = 42L,
                thumbs = null,
                videoThumbs = null,
                dcId = 4,
                attributes = emptyList(),
            ),
            altDocuments = null,
            videoCover = null,
            videoTimestamp = null,
            ttlSeconds = null,
        ),
        replyMarkup = null,
        entities = null,
        views = null,
        forwards = null,
        replies = null,
        editDate = null,
        postAuthor = null,
        groupedId = null,
        reactions = null,
        restrictionReason = null,
        ttlPeriod = null,
        quickReplyShortcutId = null,
        effect = null,
        factcheck = null,
        reportDeliveryUntilDate = null,
        paidMessageStars = null,
        suggestedPost = null,
        scheduleRepeatPeriod = null,
        summaryFromLanguage = null,
    )

    private fun shortMessage(id: Int, userId: Long, text: String) = UpdateShortMessage(
        out_ = false,
        mentioned = true,
        mediaUnread = false,
        silent = false,
        id = id,
        userId = userId,
        message = text,
        pts = 1,
        ptsCount = 1,
        date = 100,
        fwdFrom = null,
        viaBotId = null,
        replyTo = null,
        entities = null,
        ttlPeriod = null,
    )

    private fun batch(
        otherUpdates: List<org.monogram.mtproto.tl.generated.cloud.layer223.Update>,
    ) = MtProtoUpdateDifferenceBatch(
        newMessages = emptyList(),
        newEncryptedMessages = emptyList(),
        otherUpdates = otherUpdates,
        chats = emptyList(),
        users = emptyList(),
        cursor = MtProtoUpdateCursor(1, 0, 1, 1),
    )

    private fun entity(peerType: MtProtoMessagePeerType, peerId: Long, messageId: Int, date: Int = 1) =
        MtProtoMessageProjectionEntity(
            accountSlot = "account-1",
            environment = "test",
            dcId = 4,
            peerType = peerType.name,
            peerId = peerId,
            messageId = messageId,
            senderType = null,
            senderId = null,
            date = date,
            text = "message",
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

    private fun cloudEntity(id: Long, type: String, payload: ByteArray) = MtProtoCloudObjectEntity(
        sequenceId = id,
        accountSlot = "account-1",
        environment = "test",
        dcId = 4,
        objectType = type,
        payloadHash = "hash-$id",
        payload = payload,
        createdAt = 0,
    )

    private class RecordingDocumentLocations : MtProtoDocumentLocationStore by NoOpMtProtoDocumentLocationStore {
        var document: Document_be725c3b31? = null
        override suspend fun upsert(scope: MtProtoAuthKeyScope, document: Document_be725c3b31) {
            this.document = document
        }
    }

    private class FakeMessageProjectionDao : MtProtoMessageProjectionDao {
        private val entities = mutableListOf<MtProtoMessageProjectionEntity>()

        override suspend fun get(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, messageId: Int) =
            entities.firstOrNull { it.matches(accountSlot, environment, dcId, peerType, peerId, messageId) }

        override suspend fun getAll(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long) =
            entities.filter { it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId && it.peerType == peerType && it.peerId == peerId }
                .sortedWith(compareByDescending<MtProtoMessageProjectionEntity> { it.date }.thenByDescending { it.messageId })

        override suspend fun search(accountSlot: String, environment: String, dcId: Int, query: String, limit: Int, offset: Int) = entities
            .filter { it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId && !it.isDeleted && it.text?.contains(query, ignoreCase = true) == true }
            .sortedWith(compareByDescending<MtProtoMessageProjectionEntity> { it.date }.thenByDescending { it.messageId })
            .drop(offset)
            .take(limit)

        override suspend fun getPage(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, beforeDate: Int?, beforeMessageId: Int?, limit: Int) =
            getAll(accountSlot, environment, dcId, peerType, peerId)
                .filter { beforeDate == null || it.date < beforeDate || (it.date == beforeDate && it.messageId < checkNotNull(beforeMessageId)) }
                .take(limit)

        override suspend fun getLatestByPeer(accountSlot: String, environment: String, dcId: Int) =
            entities.filter { it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId }
                .groupBy { it.peerType to it.peerId }
                .values
                .map { peerMessages -> peerMessages.maxWith(compareBy<MtProtoMessageProjectionEntity> { it.date }.thenBy { it.messageId }) }
                .sortedWith(compareByDescending<MtProtoMessageProjectionEntity> { it.date }.thenByDescending { it.messageId })

        override suspend fun upsert(entity: MtProtoMessageProjectionEntity) {
            entities.removeAll { it.matches(entity.accountSlot, entity.environment, entity.dcId, entity.peerType, entity.peerId, entity.messageId) }
            entities += entity
        }

        override suspend fun markDeletedNonChannel(accountSlot: String, environment: String, dcId: Int, messageIds: List<Int>, updatedAt: Long) {
            entities.replaceAll { if (it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId && it.peerType != "CHANNEL" && it.messageId in messageIds) it.copy(isDeleted = true, updatedAt = updatedAt) else it }
        }

        override suspend fun markDeletedChannel(accountSlot: String, environment: String, dcId: Int, peerId: Long, messageIds: List<Int>, updatedAt: Long) {
            entities.replaceAll { if (it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId && it.peerType == "CHANNEL" && it.peerId == peerId && it.messageId in messageIds) it.copy(isDeleted = true, updatedAt = updatedAt) else it }
        }

        override suspend fun deleteAccount(accountSlot: String, environment: String) {
            entities.removeAll { it.accountSlot == accountSlot && it.environment == environment }
        }

        private fun MtProtoMessageProjectionEntity.matches(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long, messageId: Int) =
            this.accountSlot == accountSlot && this.environment == environment && this.dcId == dcId && this.peerType == peerType && this.peerId == peerId && this.messageId == messageId
    }

    private class FakeCloudObjectDao(private val entities: List<MtProtoCloudObjectEntity>) : MtProtoCloudObjectDao {
        override suspend fun insertAll(objects: List<MtProtoCloudObjectEntity>) = emptyList<Long>()
        override suspend fun getAll(accountSlot: String, environment: String, dcId: Int) = entities
        override suspend fun getByType(accountSlot: String, environment: String, dcId: Int, objectType: String) = entities.filter { it.objectType == objectType }
        override suspend fun deleteAccount(accountSlot: String, environment: String) = Unit
    }
}
