package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaDocument
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoServerFileReferenceRefresherTest {
    private val document = Document_be725c3b31(
        id = 42L,
        accessHash = 1L,
        fileReference = TlBytes.copyOf(byteArrayOf(9)),
        date = 0,
        mimeType = "application/octet-stream",
        size = 3L,
        thumbs = null,
        videoThumbs = null,
        dcId = 2,
        attributes = emptyList(),
    )

    @Suppress("LongParameterList")
    private fun message(id: Long, media: org.monogram.mtproto.tl.generated.cloud.layer223.MessageMedia?) =
        org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3(
            out_ = false, mentioned = false, mediaUnread = false, silent = false,
            post = false, fromScheduled = false, legacy = false, editHide = false,
            pinned = false, noforwards = false, invertMedia = false, offline = false,
            videoProcessingPending = false, paidSuggestedPostStars = false, paidSuggestedPostTon = false,
            id = id.toInt(), fromId = null, fromBoostsApplied = null, fromRank = null,
            peerId = org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel(chatId),
            savedPeerId = null, fwdFrom = null, viaBotId = null, viaBusinessBotId = null,
            replyTo = null, date = 0, message = "", media = media, replyMarkup = null,
            entities = null, views = null, forwards = null, replies = null, editDate = null,
            postAuthor = null, groupedId = null, reactions = null, restrictionReason = null,
            ttlPeriod = null, quickReplyShortcutId = null, effect = null, factcheck = null,
            reportDeliveryUntilDate = null, paidMessageStars = null, suggestedPost = null,
            scheduleRepeatPeriod = null, summaryFromLanguage = null,
        )

    private val chatId = 5L

    @Test
    fun `refreshes channel documents through channels getMessages`() = runBlocking {
        var closed = false
        val requests = mutableListOf<TlObject>()
        val transport = object : MtProtoRpcTransport {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(method: TlMethod<R>): R {
                requests += method as TlObject
                return org.monogram.mtproto.tl.generated.cloud.layer223.messages.Messages_3c331441fb(
                    listOf(message(11L, MessageMediaDocument(nopremium = false, spoiler = false, video = false, round = false, voice = false, document = document, altDocuments = null, videoCover = null, videoTimestamp = null, ttlSeconds = null))),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ) as R
            }
            override fun close() { closed = true }
        }
        val upserted = mutableListOf<Long>()
        val refresher = MtProtoServerFileReferenceRefresher(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            chats = FakeChats(MtProtoChatType.CHANNEL, accessHash = 77L),
            documentLocations = RecordingDocumentLocations(upserted),
            photoLocations = NoOpMtProtoPhotoLocationStore,
        )

        assertTrue(refresher.refresh(documentId = 42L, photoId = 0L, chatId = 5L, messageId = 11L))

        val request = requests.single() as org.monogram.mtproto.tl.generated.cloud.layer223.channels.GetMessages
        assertEquals(InputChannel_d22292516d(5L, 77L), request.channel)
        assertEquals(listOf(42L), upserted)
        assertTrue(closed)
    }

    @Test
    fun `fails closed without a cached chat or access hash`() = runBlocking {
        var opened = false
        val refresher = MtProtoServerFileReferenceRefresher(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { opened = true; error("no transport expected") },
            chats = FakeChats(MtProtoChatType.CHANNEL, accessHash = null),
            documentLocations = RecordingDocumentLocations(mutableListOf()),
            photoLocations = NoOpMtProtoPhotoLocationStore,
        )

        assertFalse(refresher.refresh(documentId = 42L, photoId = 0L, chatId = 5L, messageId = 11L))
        assertFalse(opened)
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = org.monogram.mtproto.handshake.MtProtoHandshakeConfig(2, listOf("k")),
        cloud = org.monogram.mtproto.transport.CloudLayer223ConnectionConfig(1, "d", "s", "a", "en"),
    )

    private class FakeChats(
        private val type: MtProtoChatType,
        private val accessHash: Long?,
    ) : MtProtoChatProjectionStore {
        override suspend fun upsert(scope: MtProtoAuthKeyScope, chats: List<org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e>) = Unit
        override suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long) = MtProtoChatReadModel(
            chatId = chatId,
            type = type,
            accessHash = accessHash,
            title = "chat",
            username = null,
            participantsCount = null,
            isDeleted = false, isForbidden = false, isLeft = false,
            isDeactivated = false, isVerified = false, isRestricted = false,
            isScam = false, isFake = false, isForum = false,
            signaturesEnabled = false, signatureProfilesEnabled = false,
            forumTabs = false, isMin = false,
        )
        override suspend fun getAll(scope: MtProtoAuthKeyScope) = emptyList<MtProtoChatReadModel>()
        override suspend fun backfill(scope: MtProtoAuthKeyScope) = MtProtoChatProjectionBackfillResult(0, 0)
        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
    }

    private class RecordingDocumentLocations(
        private val upserted: MutableList<Long>,
    ) : MtProtoDocumentLocationStore {
        override suspend fun upsert(scope: MtProtoAuthKeyScope, document: Document_be725c3b31) {
            upserted += document.id
        }
        override suspend fun get(scope: MtProtoAuthKeyScope, documentId: Long) = null
        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
    }
}
