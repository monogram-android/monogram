package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.FileModel
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeAnimated
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeVideo
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetSavedGifs
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SavedGifs_ed772ead35
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoGifRepositoryTest {
    @Test
    fun `stages saved GIF documents and returns opaque file handles`() = runBlocking {
        val document = Document_be725c3b31(
            id = 77L,
            accessHash = 88L,
            fileReference = TlBytes.copyOf(byteArrayOf(1)),
            date = 0,
            mimeType = "video/mp4",
            size = 100L,
            thumbs = null,
            videoThumbs = null,
            dcId = 2,
            attributes = listOf(
                DocumentAttributeAnimated,
                DocumentAttributeVideo(false, true, true, 1.0, 320, 240, null, null, null),
            ),
        )
        val transport = Transport(SavedGifs_ed772ead35(0, listOf(document)))
        val locations = Locations()
        val files = Files()
        val repository = MtProtoGifRepository(Config { config() }, MtProtoSessionTransportFactory { transport }, locations, files)

        val gifs = repository.getSavedGifs()

        assertEquals(listOf(77L), gifs.map { it.id.toLong() })
        assertEquals(501L, gifs.single().fileId)
        assertEquals(GetSavedGifs(0), transport.requests.first())
        assertEquals(listOf(77L), locations.staged)
        assertEquals(listOf(77L), files.registered)
        assertTrue(transport.closed)
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        TelegramMtProtoEndpoint(2, "dc", 443),
        MtProtoHandshakeConfig(2, listOf("key")),
        CloudLayer223ConnectionConfig(1, "d", "s", "a", "en"),
    )

    private class Config(val value: suspend () -> TelegramMtProtoBootstrapConfig) : TelegramMtProtoBootstrapConfigSource {
        override suspend fun create() = value()
    }

    private class Transport(private val response: Any) : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            @Suppress("UNCHECKED_CAST")
            return response as R
        }
        override fun close() { closed = true }
    }

    private class Locations : MtProtoDocumentLocationStore by NoOpMtProtoDocumentLocationStore {
        val staged = mutableListOf<Long>()
        override suspend fun upsert(scope: MtProtoAuthKeyScope, document: Document_be725c3b31) {
            staged += document.id
        }
    }

    private class Files : MtProtoFileRepository {
        val registered = mutableListOf<Long>()
        override val fileDownloadFlow: Flow<FileDownloadEvent> = emptyFlow()
        override val messageDownloadFlow = emptyFlow<org.monogram.domain.models.MessageDownloadEvent>()
        override suspend fun registerDocument(documentId: Long, chatId: Long, messageId: Long) = registerDocument(documentId)
        override suspend fun registerDocument(documentId: Long): MtProtoDocumentFile? {
            registered += documentId
            return MtProtoDocumentFile(501, documentId, "", "video/mp4", 100)
        }
        override suspend fun registerPhoto(photoId: Long, chatId: Long, messageId: Long): MtProtoPhotoFile? = null
        override fun download(fileId: Int, offset: Long, limit: Long) = Unit
        override suspend fun cancel(fileId: Int) = Unit
        override suspend fun getPath(fileId: Int): String? = null
        override suspend fun getInfo(fileId: Int): FileModel? = null
    }
}
