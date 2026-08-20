package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.StickerFormat
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.models.StickerType
import org.monogram.domain.repository.StickerRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeCustomEmoji
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeImageSize
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeSticker
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeVideo
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.InputStickerSetShortName
import org.monogram.mtproto.tl.generated.cloud.layer223.InputStickerSet
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ClearRecentStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetRecentStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetStickerSet
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.FoundStickers_7d9ce2d574
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SearchStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.RecentStickers_ee91009b24
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.StickerSet_ec0b3f33d3

/** Metadata-only MTProto sticker reads; file paths and mutations require media projections. */
internal class MtProtoStickerRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : StickerRepository {
    private val unsupportedSets = MutableStateFlow<List<StickerSetModel>>(emptyList())

    override val installedStickerSets: StateFlow<List<StickerSetModel>> = unsupportedSets
    override val customEmojiStickerSets: StateFlow<List<StickerSetModel>> = unsupportedSets
    override val archivedStickerSets: StateFlow<List<StickerSetModel>> = unsupportedSets
    override val archivedEmojiSets: StateFlow<List<StickerSetModel>> = unsupportedSets

    override suspend fun loadInstalledStickerSets() = unsupported("sticker-set refresh")
    override suspend fun loadCustomEmojiStickerSets() = unsupported("custom emoji refresh")
    override suspend fun loadArchivedStickerSets() = unsupported("archived sticker refresh")
    override suspend fun loadArchivedEmojiSets() = unsupported("archived emoji refresh")
    override suspend fun getRecentStickers(): List<StickerModel> =
        transportFactory.open(accountSlot).use { transport ->
            val result = transport.execute(GetRecentStickers(attached = false, hash = 0))
                as? RecentStickers_ee91009b24
                ?: error("Unsupported MTProto recent stickers response")
            result.stickers.mapNotNull { (it as? Document_be725c3b31)?.toDomain() }
        }

    override suspend fun clearRecentStickers() {
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(ClearRecentStickers(attached = false))) {
                "MTProto recent sticker clearing was rejected"
            }
        }
    }
    override fun getStickerFile(fileId: Long): Flow<String?> = unsupported("sticker files")
    override fun getCustomEmojiFile(customEmojiId: Long): Flow<String?> = unsupported("custom emoji files")
    override suspend fun getTgsJson(path: String): String? = unsupported("animated sticker files")
    override fun clearCache() = unsupported("sticker cache clearing")

    override suspend fun getStickerSet(setId: Long): StickerSetModel? = unsupported("sticker-set ID reads without access hash")

    override suspend fun getStickerSetByName(name: String): StickerSetModel? =
        getStickerSet(InputStickerSetShortName(name.trim()))

    private suspend fun getStickerSet(input: InputStickerSet): StickerSetModel? {
        require(input !is InputStickerSetShortName || input.shortName.isNotBlank()) {
            "Sticker-set short name must not be blank"
        }
        configSource.createForAccount(accountSlot)
        val transport = transportFactory.open(accountSlot)
        try {
            val result = transport.execute(GetStickerSet(input, 0)) as? StickerSet_ec0b3f33d3 ?: return null
            return result.toDomain()
        } finally {
            transport.close()
        }
    }

    override suspend fun verifyStickerSet(setId: Long) = unsupported("sticker verification")
    override suspend fun toggleStickerSetInstalled(setId: Long, isInstalled: Boolean) = unsupported("sticker installation")
    override suspend fun toggleStickerSetArchived(setId: Long, isArchived: Boolean) = unsupported("sticker archive mutation")
    override suspend fun reorderStickerSets(stickerType: StickerRepository.TdLibStickerType, stickerSetIds: List<Long>) = unsupported("sticker ordering")
    override suspend fun searchStickers(query: String): List<StickerModel> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        val config = configSource.createForAccount(accountSlot)
        return transportFactory.open(accountSlot).use { transport ->
            val result = transport.execute(
                SearchStickers(
                    emojis = false,
                    q = normalized,
                    emoticon = "",
                    langCode = listOf(config.cloud.systemLanguageCode),
                    offset = 0,
                    limit = SEARCH_LIMIT,
                    hash = 0,
                ),
            ) as? FoundStickers_7d9ce2d574 ?: error("Unsupported MTProto sticker search response")
            result.stickers.mapNotNull { (it as? Document_be725c3b31)?.toDomain() }
        }
    }
    override suspend fun getStickerEmojiHints(query: String): List<String> = unsupported("sticker emoji hints")
    override suspend fun searchStickerSets(query: String): List<StickerSetModel> = unsupported("sticker-set search")

    private fun StickerSet_ec0b3f33d3.toDomain(): StickerSetModel? {
        val set = set_ as? org.monogram.mtproto.tl.generated.cloud.layer223.StickerSet_97ab856701 ?: return null
        return StickerSetModel(
            id = set.id,
            title = set.title,
            name = set.shortName,
            stickers = documents.mapNotNull { (it as? Document_be725c3b31)?.toDomain() },
            isInstalled = set.installedDate != null,
            isArchived = set.archived,
            isOfficial = set.official,
            stickerType = when {
                set.emojis -> StickerType.CUSTOM_EMOJI
                set.masks -> StickerType.MASK
                else -> StickerType.REGULAR
            },
        )
    }

    private fun Document_be725c3b31.toDomain(): StickerModel? {
        val sticker = attributes.filterIsInstance<DocumentAttributeSticker>().firstOrNull()
        val customEmoji = attributes.filterIsInstance<DocumentAttributeCustomEmoji>().firstOrNull()
        if (sticker == null && customEmoji == null) return null
        val dimensions = attributes.filterIsInstance<DocumentAttributeImageSize>().firstOrNull()
        val video = attributes.filterIsInstance<DocumentAttributeVideo>().firstOrNull()
        return StickerModel(
            id = id,
            customEmojiId = customEmoji?.let { id },
            width = dimensions?.w ?: video?.w ?: 0,
            height = dimensions?.h ?: video?.h ?: 0,
            emoji = sticker?.alt ?: customEmoji?.alt.orEmpty(),
            path = null,
            format = when {
                video != null -> StickerFormat.VIDEO
                mimeType == "application/x-tgsticker" -> StickerFormat.ANIMATED
                mimeType == "video/webm" -> StickerFormat.VIDEO
                else -> StickerFormat.STATIC
            },
        )
    }

    private fun unsupported(operation: String): Nothing = throw UnsupportedOperationException(
        "MTProto $operation is not available"
    )

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val SEARCH_LIMIT = 100
    }
}
