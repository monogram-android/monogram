package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputStickerSetShortName
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ArchivedStickers_8455cc1f39
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ClearRecentStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetArchivedStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetRecentStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.RecentStickers_ee91009b24
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.FoundStickers_7d9ce2d574
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SearchStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.FoundStickerSets_215fe0f754
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SearchStickerSets
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetStickerSet
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.FileModel
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoStickerRepositoryTest {
    @Test
    fun `rejects ID-only sticker reads before opening transport`() = runBlocking {
        var opened = false
        val repository = repository { opened = true; Transport() }

        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { repository.getStickerSet(42L) }
        }
        assertEquals(false, opened)
    }

    @Test
    fun `rejects blank sticker set name before opening transport`() = runBlocking {
        var opened = false
        val repository = repository { opened = true; Transport() }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.getStickerSetByName("  ") }
        }
        assertEquals(false, opened)
    }

    @Test
    fun `loads regular and emoji archived sticker state`() = runBlocking {
        val transport = Transport(
            ArchivedStickers_8455cc1f39(0, emptyList()),
            ArchivedStickers_8455cc1f39(0, emptyList()),
        )
        val repository = repository { transport }

        repository.loadArchivedStickerSets()
        repository.loadArchivedEmojiSets()

        assertEquals(emptyList<Any>(), repository.archivedStickerSets.value)
        assertEquals(emptyList<Any>(), repository.archivedEmojiSets.value)
        assertEquals(GetArchivedStickers(masks = false, emojis = false, offsetId = 0, limit = 100), transport.requests[0])
        assertEquals(GetArchivedStickers(masks = false, emojis = true, offsetId = 0, limit = 100), transport.requests[1])
        assertEquals(true, transport.closed)
    }

    @Test
    fun `reads and clears standard recent stickers through selected transport`() = runBlocking {
        val transport = Transport(RecentStickers_ee91009b24(0, emptyList(), emptyList(), emptyList()), true)
        val repository = repository { transport }

        assertEquals(emptyList<Any>(), repository.getRecentStickers())
        repository.clearRecentStickers()

        assertEquals(GetRecentStickers(attached = false, hash = 0), transport.requests[0])
        assertEquals(ClearRecentStickers(attached = false), transport.requests[1])
        assertEquals(true, transport.closed)
    }

    @Test
    fun `searches stickers with configured language`() = runBlocking {
        val transport = Transport(FoundStickers_7d9ce2d574(null, 0, emptyList()))
        val repository = repository { transport }

        assertEquals(emptyList<Any>(), repository.searchStickers("smile"))
        assertEquals(SearchStickers(false, "smile", "", listOf("en"), 0, 100, 0), transport.request)
        assertEquals(true, transport.closed)
    }

    @Test
    fun `does not open transport for blank sticker search`() = runBlocking {
        var opened = false
        val repository = repository { opened = true; Transport(null) }

        assertEquals(emptyList<Any>(), repository.searchStickers("  "))
        assertEquals(false, opened)
    }

    @Test
    fun `searches sticker sets through selected transport`() = runBlocking {
        val transport = Transport(FoundStickerSets_215fe0f754(0, emptyList()))
        val repository = repository { transport }

        assertEquals(emptyList<Any>(), repository.searchStickerSets("cats"))
        assertEquals(SearchStickerSets(excludeFeatured = false, q = "cats", hash = 0), transport.request)
        assertEquals(true, transport.closed)
    }

    @Test
    fun `does not open transport for blank sticker set search`() = runBlocking {
        var opened = false
        val repository = repository { opened = true; Transport(null) }

        assertEquals(emptyList<Any>(), repository.searchStickerSets("  "))
        assertEquals(false, opened)
    }

    @Test
    fun `uses short name input and closes selected transport`() = runBlocking {
        val transport = Transport(null)
        val repository = repository { transport }

        assertEquals(null, repository.getStickerSetByName("monogram"))
        val request = transport.request as GetStickerSet
        assertEquals("monogram", (request.stickerset as InputStickerSetShortName).shortName)
        assertEquals(true, transport.closed)
    }

    private fun repository(factory: () -> MtProtoRpcTransport) = MtProtoStickerRepository(
        configSource = object : TelegramMtProtoBootstrapConfigSource {
            override suspend fun create() = config()
        },
        transportFactory = MtProtoSessionTransportFactory { factory() },
        locations = NoOpMtProtoDocumentLocationStore,
        files = Files(),
    )

    @Test
    fun `emits null without downloading an unknown sticker document`() = runBlocking {
        val files = Files()
        val repository = MtProtoStickerRepository(
            configSource = object : TelegramMtProtoBootstrapConfigSource {
                override suspend fun create() = config()
            },
            transportFactory = MtProtoSessionTransportFactory { Transport(null) },
            locations = NoOpMtProtoDocumentLocationStore,
            files = files,
        )

        assertEquals(null, repository.getStickerFile(77L).first())
        assertEquals(emptyList<Int>(), files.downloads)
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        TelegramMtProtoEndpoint(2, "dc", 443),
        MtProtoHandshakeConfig(2, listOf("key")),
        CloudLayer223ConnectionConfig(1, "d", "s", "a", "en"),
    )

    @Test
    fun `downloads a known sticker through an opaque handle`() = runBlocking {
        val files = Files(document = MtProtoDocumentFile(501, 77L, "sticker.webp", "image/webp", 100))
        val repository = MtProtoStickerRepository(
            configSource = object : TelegramMtProtoBootstrapConfigSource { override suspend fun create() = config() },
            transportFactory = MtProtoSessionTransportFactory { Transport(null) },
            locations = NoOpMtProtoDocumentLocationStore,
            files = files,
        )

        assertEquals("/tmp/sticker.webp", repository.getStickerFile(77L).first())
        assertEquals(listOf(77L), files.registered)
        assertEquals(listOf(501), files.downloads)
    }

    private class Files(
        private val document: MtProtoDocumentFile? = null,
    ) : MtProtoFileRepository {
        val registered = mutableListOf<Long>()
        val downloads = mutableListOf<Int>()
        private val events = MutableSharedFlow<FileDownloadEvent>(extraBufferCapacity = 1)
        override val fileDownloadFlow: Flow<FileDownloadEvent> = events
        override val messageDownloadFlow = emptyFlow<org.monogram.domain.models.MessageDownloadEvent>()
        override suspend fun registerDocument(documentId: Long, chatId: Long, messageId: Long) = registerDocument(documentId)
        override suspend fun registerDocument(documentId: Long): MtProtoDocumentFile? {
            registered += documentId
            return document
        }
        override suspend fun registerPhoto(photoId: Long, chatId: Long, messageId: Long) = null
        override fun download(fileId: Int, offset: Long, limit: Long) {
            downloads += fileId
            events.tryEmit(FileDownloadEvent.Completed(fileId, "/tmp/sticker.webp"))
        }
        override suspend fun cancel(fileId: Int) = Unit
        override suspend fun getPath(fileId: Int): String? = null
        override suspend fun getInfo(fileId: Int): FileModel? = null
    }

    private class Transport(vararg responses: Any?) : MtProtoRpcTransport {
        private val responses = responses.toList()
        val requests = mutableListOf<TlMethod<*>>()
        val request: TlMethod<*> get() = requests.single()
        var closed = false
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            @Suppress("UNCHECKED_CAST")
            return responses[requests.lastIndex] as R
        }
        override fun close() { closed = true }
    }
}
