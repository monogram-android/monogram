package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoWallpaperRepository
import org.monogram.domain.models.WallpaperModel
import org.monogram.domain.repository.WallpaperRepository

/** Keeps TDLib/file-backed wallpaper operations out of selected MTProto accounts. */
internal class TelegramBackendWallpaperRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> WallpaperRepository,
    private val mtProtoFactory: () -> MtProtoWallpaperRepository = { throw UnsupportedOperationException("MTProto wallpapers are not configured") },
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : WallpaperRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override fun getWallpapers(): Flow<List<WallpaperModel>> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getWallpapers()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.wallpapers()
    }

    override suspend fun downloadWallpaper(fileId: Int) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.downloadWallpaper(fileId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.download(fileId)
    }

    override suspend fun setDefaultWallpaper(wallpaper: WallpaperModel, isBlurred: Boolean, isMoving: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setDefaultWallpaper(wallpaper, isBlurred, isMoving)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun uploadWallpaper(filePath: String, isBlurred: Boolean, isMoving: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.uploadWallpaper(filePath, isBlurred, isMoving)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "MTProto wallpaper media is not available"
    )

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
