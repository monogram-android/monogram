package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import org.monogram.domain.models.WallpaperModel

interface WallpaperRepository {
    fun getWallpapers(): Flow<List<WallpaperModel>>
    suspend fun downloadWallpaper(fileId: Int)
}