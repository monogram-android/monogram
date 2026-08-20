package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.repository.StickerRepository

/** Serves persisted sticker projections while MTProto sticker/file RPC parity is incomplete. */
internal class MtProtoStickerRepository(
    localDataSource: StickerLocalDataSource,
    scope: CoroutineScope,
) : StickerRepository {
    override val installedStickerSets: StateFlow<List<StickerSetModel>> = localDataSource
        .getInstalledStickerSetsByType(REGULAR_TYPE)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val customEmojiStickerSets: StateFlow<List<StickerSetModel>> = localDataSource
        .getInstalledStickerSetsByType(CUSTOM_EMOJI_TYPE)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val archivedStickerSets: StateFlow<List<StickerSetModel>> = localDataSource
        .getArchivedStickerSetsByType(REGULAR_TYPE)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val archivedEmojiSets: StateFlow<List<StickerSetModel>> = localDataSource
        .getArchivedStickerSetsByType(CUSTOM_EMOJI_TYPE)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val local = localDataSource

    override suspend fun loadInstalledStickerSets() = unsupported("sticker-set refresh")
    override suspend fun loadCustomEmojiStickerSets() = unsupported("custom emoji refresh")
    override suspend fun loadArchivedStickerSets() = unsupported("archived sticker refresh")
    override suspend fun loadArchivedEmojiSets() = unsupported("archived emoji refresh")

    override suspend fun getRecentStickers(): List<StickerModel> = unsupported("recent stickers")
    override suspend fun clearRecentStickers() = unsupported("recent sticker mutation")

    override fun getStickerFile(fileId: Long): Flow<String?> = flow {
        emit(local.getPath(fileId))
    }

    override fun getCustomEmojiFile(customEmojiId: Long): Flow<String?> = unsupported("custom emoji files")
    override suspend fun getTgsJson(path: String): String? = unsupported("animated sticker files")
    override fun clearCache() = Unit

    override suspend fun getStickerSet(setId: Long): StickerSetModel? = local.getStickerSetById(setId)
    override suspend fun getStickerSetByName(name: String): StickerSetModel? = local.getStickerSetByName(name)
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

    private companion object {
        const val REGULAR_TYPE = "REGULAR"
        const val CUSTOM_EMOJI_TYPE = "CUSTOM_EMOJI"
    }
}
