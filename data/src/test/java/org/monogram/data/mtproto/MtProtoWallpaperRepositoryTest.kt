package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.FileModel
import org.monogram.domain.models.MessageDownloadEvent
import org.monogram.domain.models.WallpaperType
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.WallPaperNoFile
import org.monogram.mtproto.tl.generated.cloud.layer223.WallPaper_7e3ce0b613
import org.monogram.mtproto.tl.generated.cloud.layer223.WallPaperSettings_2cd7142740
import org.monogram.mtproto.tl.generated.cloud.layer223.account.WallPapers_1d62475ab5
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoWallpaperRepositoryTest {
    @Test
    fun `maps no-file wallpaper using only authoritative rendering fields`() = runBlocking {
        val repository = MtProtoWallpaperRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory {
                ResultTransport(
                    WallPapers_1d62475ab5(
                        hash = 8L,
                        wallpapers = listOf(
                            WallPaperNoFile(
                                id = 7L,
                                default = true,
                                dark = false,
                                settings = WallPaperSettings_2cd7142740(
                                    blur = false,
                                    motion = true,
                                    backgroundColor = 1,
                                    secondBackgroundColor = 2,
                                    thirdBackgroundColor = null,
                                    fourthBackgroundColor = null,
                                    intensity = 40,
                                    rotation = 45,
                                    emoticon = null,
                                ),
                            ),
                        ),
                    ),
                )
            },
            documents = NoOpMtProtoDocumentLocationStore,
            files = Files,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val wallpaper = repository.wallpapers().first { it.isNotEmpty() }.single()

        assertEquals(7L, wallpaper.id)
        assertEquals(WallpaperType.FILL, wallpaper.type)
        assertTrue(wallpaper.isDownloaded)
        assertEquals(1, wallpaper.settings?.backgroundColor)
        assertEquals(true, wallpaper.settings?.isMoving)
        assertEquals(null, wallpaper.documentId.takeIf { it != 0L })
    }

    @Test
    fun `updates file-backed wallpaper when its owned download completes`() = runBlocking {
        val files = EventFiles()
        val repository = MtProtoWallpaperRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory {
                ResultTransport(WallPapers_1d62475ab5(0, listOf(fileWallpaper())))
            },
            documents = RecordingDocuments,
            files = files,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(42L, repository.wallpapers().first { it.isNotEmpty() }.single().documentId)
        files.complete(42, "/owned/wallpaper.jpg")

        val wallpaper = repository.wallpapers().first { it.single().localPath != null }.single()
        assertTrue(wallpaper.isDownloaded)
        assertEquals("/owned/wallpaper.jpg", wallpaper.localPath)
        assertEquals(9L, RecordingDocuments.documentId)
    }

    @Test
    fun `delegates wallpaper downloads to the owned opaque file handle`() {
        MtProtoWallpaperRepositoryImpl(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { ResultTransport(WallPapers_1d62475ab5(0, emptyList())) },
            documents = NoOpMtProtoDocumentLocationStore,
            files = Files,
            scope = CoroutineScope(Dispatchers.Unconfined),
        ).download(42)

        assertEquals(42, Files.downloadedFileId)
    }

    private fun fileWallpaper() = WallPaper_7e3ce0b613(
        id = 7L,
        creator = false,
        default = false,
        pattern = false,
        dark = false,
        accessHash = 8L,
        slug = "owned",
        document = Document_be725c3b31(
            id = 9L,
            accessHash = 10L,
            fileReference = TlBytes.copyOf(byteArrayOf(1)),
            date = 0,
            mimeType = "image/jpeg",
            size = 12L,
            thumbs = null,
            videoThumbs = null,
            dcId = 2,
            attributes = emptyList(),
        ),
        settings = null,
    )

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )

    private class ResultTransport(private val result: Any) : MtProtoRpcTransport {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R = result as R
        override fun close() = Unit
    }

    private object RecordingDocuments : MtProtoDocumentLocationStore by NoOpMtProtoDocumentLocationStore {
        var documentId: Long? = null
        override suspend fun upsert(scope: MtProtoAuthKeyScope, document: Document_be725c3b31) {
            documentId = document.id
        }
    }

    private class EventFiles : MtProtoFileRepository {
        private val events = MutableSharedFlow<FileDownloadEvent>(extraBufferCapacity = 1)
        override val fileDownloadFlow: Flow<FileDownloadEvent> = events
        override val messageDownloadFlow: Flow<MessageDownloadEvent> = emptyFlow()
        override suspend fun registerDocument(documentId: Long, chatId: Long, messageId: Long) = null
        override suspend fun registerDocument(documentId: Long) = MtProtoDocumentFile(42, documentId, "wallpaper.jpg", "image/jpeg", 12)
        override suspend fun registerPhoto(photoId: Long, chatId: Long, messageId: Long) = null
        override fun download(fileId: Int, offset: Long, limit: Long) = Unit
        override suspend fun cancel(fileId: Int) = Unit
        override suspend fun getPath(fileId: Int) = null
        override suspend fun getInfo(fileId: Int): FileModel? = null
        fun complete(fileId: Int, path: String) = check(events.tryEmit(FileDownloadEvent.Completed(fileId, path)))
    }

    private object Files : MtProtoFileRepository {
        var downloadedFileId: Int? = null
        override val fileDownloadFlow: Flow<FileDownloadEvent> = emptyFlow()
        override val messageDownloadFlow: Flow<MessageDownloadEvent> = emptyFlow()
        override suspend fun registerDocument(documentId: Long, chatId: Long, messageId: Long) = null
        override suspend fun registerDocument(documentId: Long) = null
        override suspend fun registerPhoto(photoId: Long, chatId: Long, messageId: Long) = null
        override fun download(fileId: Int, offset: Long, limit: Long) { downloadedFileId = fileId }
        override suspend fun cancel(fileId: Int) = Unit
        override suspend fun getPath(fileId: Int) = null
        override suspend fun getInfo(fileId: Int): FileModel? = null
    }
}
