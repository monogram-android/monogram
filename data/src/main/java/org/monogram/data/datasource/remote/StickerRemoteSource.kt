package org.monogram.data.datasource.remote

import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.models.StickerType

interface StickerRemoteSource {
    suspend fun getInstalledStickerSets(type: StickerType): List<StickerSetModel>
    suspend fun getArchivedStickerSets(type: StickerType): List<StickerSetModel>
    suspend fun getStickerSet(setId: Long): StickerSetModel?
    suspend fun getStickerSetByName(name: String): StickerSetModel?
    suspend fun getRecentStickers(): List<StickerModel>
    suspend fun toggleStickerSetInstalled(setId: Long, isInstalled: Boolean)
    suspend fun toggleStickerSetArchived(setId: Long, isArchived: Boolean)
    suspend fun reorderStickerSets(type: StickerType, setIds: List<Long>)
    suspend fun searchStickers(query: String): List<StickerModel>
    suspend fun searchStickerSets(query: String): List<StickerSetModel>
    suspend fun clearRecentStickers()
}