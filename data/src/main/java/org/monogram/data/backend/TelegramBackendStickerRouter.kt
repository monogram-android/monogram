package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoStickerRepository
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.repository.StickerRepository

internal class TelegramBackendStickerRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> StickerRepository,
    private val mtProtoFactory: () -> MtProtoStickerRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : StickerRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override val installedStickerSets: StateFlow<List<StickerSetModel>> get() = selected().installedStickerSets
    override val customEmojiStickerSets: StateFlow<List<StickerSetModel>> get() = selected().customEmojiStickerSets
    override val archivedStickerSets: StateFlow<List<StickerSetModel>> get() = selected().archivedStickerSets
    override val archivedEmojiSets: StateFlow<List<StickerSetModel>> get() = selected().archivedEmojiSets

    override suspend fun loadInstalledStickerSets() = selected().loadInstalledStickerSets()
    override suspend fun loadCustomEmojiStickerSets() = selected().loadCustomEmojiStickerSets()
    override suspend fun loadArchivedStickerSets() = selected().loadArchivedStickerSets()
    override suspend fun loadArchivedEmojiSets() = selected().loadArchivedEmojiSets()
    override suspend fun getRecentStickers() = selected().getRecentStickers()
    override suspend fun clearRecentStickers() = selected().clearRecentStickers()
    override fun getStickerFile(fileId: Long): Flow<String?> = selected().getStickerFile(fileId)
    override fun getCustomEmojiFile(customEmojiId: Long): Flow<String?> = selected().getCustomEmojiFile(customEmojiId)
    override suspend fun getTgsJson(path: String) = selected().getTgsJson(path)
    override fun clearCache() = selected().clearCache()
    override suspend fun getStickerSet(setId: Long) = selected().getStickerSet(setId)
    override suspend fun getStickerSetByName(name: String) = selected().getStickerSetByName(name)
    override suspend fun verifyStickerSet(setId: Long) = selected().verifyStickerSet(setId)
    override suspend fun toggleStickerSetInstalled(setId: Long, isInstalled: Boolean) = selected().toggleStickerSetInstalled(setId, isInstalled)
    override suspend fun toggleStickerSetArchived(setId: Long, isArchived: Boolean) = selected().toggleStickerSetArchived(setId, isArchived)
    override suspend fun reorderStickerSets(stickerType: StickerRepository.TdLibStickerType, stickerSetIds: List<Long>) = selected().reorderStickerSets(stickerType, stickerSetIds)
    override suspend fun searchStickers(query: String) = selected().searchStickers(query)
    override suspend fun getStickerEmojiHints(query: String) = selected().getStickerEmojiHints(query)
    override suspend fun searchStickerSets(query: String) = selected().searchStickerSets(query)

    private fun selected(): StickerRepository = when (selectedBackend.value) {
        TelegramBackendKind.LEGACY -> legacy
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto
        null -> error("Telegram backend selection is not loaded")
    }

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
