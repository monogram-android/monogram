package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.mtproto.MtProtoWallpaperRepository
import org.monogram.domain.models.WallpaperModel

class TelegramBackendWallpaperRouterTest {
    @Test
    fun `selected MTProto wallpaper reads and downloads avoid legacy repository`() = runBlocking {
        var downloaded: Int? = null
        var defaultRequest: Triple<Long, Boolean, Boolean>? = null
        val router = TelegramBackendWallpaperRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy wallpaper repository must not be created") },
            mtProtoFactory = {
                object : MtProtoWallpaperRepository {
                    override fun wallpapers() = flowOf(listOf(wallpaper))
                    override fun download(fileId: Int) { downloaded = fileId }
                    override suspend fun setDefault(wallpaperId: Long, isBlurred: Boolean, isMoving: Boolean) =
                        wallpaper.also { defaultRequest = Triple(wallpaperId, isBlurred, isMoving) }
                }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(listOf(wallpaper), router.getWallpapers().value())
        router.downloadWallpaper(42)
        assertEquals(wallpaper, router.setDefaultWallpaper(wallpaper, isBlurred = true, isMoving = false))

        assertEquals(42, downloaded)
        assertEquals(Triple(7L, true, false), defaultRequest)
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }

    private companion object {
        val wallpaper = WallpaperModel(
            id = 7,
            slug = "owned",
            title = "owned",
            pattern = false,
            documentId = 42,
            thumbnail = null,
            settings = null,
            isDownloaded = false,
            localPath = null,
        )
    }

    private suspend fun <T> Flow<T>.value(): T = first()
}
