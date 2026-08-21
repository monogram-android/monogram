package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
import org.monogram.mtproto.tl.generated.cloud.layer223.WallPaperNoFile
import org.monogram.mtproto.tl.generated.cloud.layer223.WallPaperSettings_2cd7142740
import org.monogram.mtproto.tl.generated.cloud.layer223.account.WallPapers_1d62475ab5
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
