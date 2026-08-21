package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.WallpaperModel
import org.monogram.domain.repository.WallpaperRepository

/** Keeps Telegram/file-backed wallpaper operations out of selected MTProto accounts. */
internal class MtProtoWallpaperAdapter(
    private val mtProtoFactory: () -> MtProtoWallpaperRepository,
) : WallpaperRepository {
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    override fun getWallpapers(): Flow<List<WallpaperModel>> = mtProto.wallpapers()

    override suspend fun downloadWallpaper(fileId: Int) = mtProto.download(fileId)

    override suspend fun setDefaultWallpaper(wallpaper: WallpaperModel, isBlurred: Boolean, isMoving: Boolean) = mtProto.setDefault(wallpaper.id, isBlurred, isMoving)

    override suspend fun uploadWallpaper(filePath: String, isBlurred: Boolean, isMoving: Boolean) = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "MTProto wallpaper media is not available"
    )

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
