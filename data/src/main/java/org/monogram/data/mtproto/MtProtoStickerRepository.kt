package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.repository.StickerRepository

/** Explicit boundary until sticker projections and file state are account-scoped. */
internal class MtProtoStickerRepository : StickerRepository {
    private val unsupportedSets = MutableStateFlow<List<StickerSetModel>>(emptyList())

    override val installedStickerSets: StateFlow<List<StickerSetModel>> = unsupportedSets
    override val customEmojiStickerSets: StateFlow<List<StickerSetModel>> = unsupportedSets
    override val archivedStickerSets: StateFlow<List<StickerSetModel>> = unsupportedSets
    override val archivedEmojiSets: StateFlow<List<StickerSetModel>> = unsupportedSets

    override suspend fun loadInstalledStickerSets() = unsupported("sticker-set refresh")
    override suspend fun loadCustomEmojiStickerSets() = unsupported("custom emoji refresh")
    override suspend fun loadArchivedStickerSets() = unsupported("archived sticker refresh")
    override suspend fun loadArchivedEmojiSets() = unsupported("archived emoji refresh")
    override suspend fun getRecentStickers(): List<StickerModel> = unsupported("recent stickers")
    override suspend fun clearRecentStickers() = unsupported("recent sticker mutation")
    override fun getStickerFile(fileId: Long): Flow<String?> = unsupported("sticker files")
    override fun getCustomEmojiFile(customEmojiId: Long): Flow<String?> = unsupported("custom emoji files")
    override suspend fun getTgsJson(path: String): String? = unsupported("animated sticker files")
    override fun clearCache() = unsupported("sticker cache clearing")
    override suspend fun getStickerSet(setId: Long): StickerSetModel? = unsupported("sticker-set reads")
    override suspend fun getStickerSetByName(name: String): StickerSetModel? = unsupported("sticker-set reads")
    override suspend fun verifyStickerSet(setId: Long) = unsupported("sticker verification")
    override suspend fun toggleStickerSetInstalled(setId: Long, isInstalled: Boolean) = unsupported("sticker installation")
    override suspend fun toggleStickerSetArchived(setId: Long, isArchived: Boolean) = unsupported("sticker archive mutation")
    override suspend fun reorderStickerSets(stickerType: StickerRepository.TdLibStickerType, stickerSetIds: List<Long>) = unsupported("sticker ordering")
    override suspend fun searchStickers(query: String): List<StickerModel> = unsupported("sticker search")
    override suspend fun getStickerEmojiHints(query: String): List<String> = unsupported("sticker emoji hints")
    override suspend fun searchStickerSets(query: String): List<StickerSetModel> = unsupported("sticker-set search")

    private fun unsupported(operation: String): Nothing = throw UnsupportedOperationException(
        "MTProto $operation is not available"
    )
}
