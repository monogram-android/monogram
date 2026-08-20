package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.MessageDownloadEvent
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Messages_3c331441fb
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Search
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoProfilePhotoRepositoryTest {
    @Test
    fun `loads chat photo history through authoritative query staging`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoProfilePhotoRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = NoOpMtProtoUserProjectionStore,
            chats = BasicGroupStore,
            resultStager = MtProtoHistoryResultStager(),
            locations = NoOpMtProtoPhotoLocationStore,
            files = NoOpFiles,
        )

        val photos = repository.getChatProfilePhotos(-42, offset = 3, limit = 20)

        assertEquals(emptyList<Any>(), photos)
        val request = transport.requests.single() as Search
        assertEquals(org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat(42), request.peer)
        assertEquals(org.monogram.mtproto.tl.generated.cloud.layer223.InputMessagesFilterChatPhotos, request.filter)
        assertEquals(3, request.addOffset)
        assertEquals(20, request.limit)
        assertEquals(emptyList<Any>(), repository.getChatProfilePhotosFlow(-42).firstValue())
        assertTrue(transport.closed)
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )

    private object BasicGroupStore : MtProtoChatProjectionStore by NoOpMtProtoChatProjectionStore {
        override suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long) = MtProtoChatReadModel(
            chatId = chatId,
            type = MtProtoChatType.BASIC_GROUP,
            accessHash = null,
            title = "Group",
            username = null,
            participantsCount = null,
            isDeleted = false,
            isForbidden = false,
            isLeft = false,
            isDeactivated = false,
            isVerified = false,
            isRestricted = false,
            isScam = false,
            isFake = false,
            isForum = false,
            signaturesEnabled = false,
            signatureProfilesEnabled = false,
            forumTabs = false,
            isMin = false,
        )
    }

    private class RecordingTransport : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            return Messages_3c331441fb(emptyList(), emptyList(), emptyList(), emptyList()) as R
        }

        override fun close() {
            closed = true
        }
    }

    private object NoOpFiles : MtProtoFileRepository {
        override val fileDownloadFlow: Flow<FileDownloadEvent> = emptyFlow()
        override val messageDownloadFlow: Flow<MessageDownloadEvent> = emptyFlow()
        override suspend fun registerDocument(documentId: Long, chatId: Long, messageId: Long) = null
        override suspend fun registerDocument(documentId: Long) = null
        override suspend fun registerPhoto(photoId: Long, chatId: Long, messageId: Long) = null
        override fun download(fileId: Int, offset: Long, limit: Long) = Unit
        override suspend fun cancel(fileId: Int) = Unit
        override suspend fun getPath(fileId: Int) = null
        override suspend fun getInfo(fileId: Int) = null
    }

    private suspend fun <T> Flow<T>.firstValue(): T = first()
}
