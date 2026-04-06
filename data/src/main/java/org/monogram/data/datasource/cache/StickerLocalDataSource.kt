package org.monogram.data.datasource.cache

import kotlinx.coroutines.flow.Flow
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerSetModel

interface StickerLocalDataSource {
    fun getInstalledStickerSetsByType(type: String): Flow<List<StickerSetModel>>
    fun getArchivedStickerSetsByType(type: String): Flow<List<StickerSetModel>>
    suspend fun getStickerSetById(id: Long): StickerSetModel?
    suspend fun getStickerSetByName(name: String): StickerSetModel?
    suspend fun saveStickerSets(
        sets: List<StickerSetModel>,
        type: String,
        isInstalled: Boolean,
        isArchived: Boolean
    )

    suspend fun insertStickerSet(set: StickerSetModel, type: String)
    suspend fun clearStickerSets()

    suspend fun getPath(fileId: Long): String?
    suspend fun insertPath(fileId: Long, path: String)
    suspend fun deletePath(fileId: Long)
    suspend fun clearPaths()

    fun getRecentEmojis(): Flow<List<RecentEmojiModel>>
    suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel)
    suspend fun clearRecentEmojis()
}