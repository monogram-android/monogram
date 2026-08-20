package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.StickerFormat
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.models.StickerType
import org.monogram.domain.repository.StickerRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeCustomEmoji
import org.monogram.mtproto.tl.generated.cloud.layer223.StickerSetCovered_1af4b31f79
import org.monogram.mtproto.tl.generated.cloud.layer223.StickerSetCovered_34353f5c94
import org.monogram.mtproto.tl.generated.cloud.layer223.StickerSetFullCovered
import org.monogram.mtproto.tl.generated.cloud.layer223.StickerSetMultiCovered
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
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.FoundStickerSets_215fe0f754
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SearchStickerSets
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.RecentStickers_ee91009b24
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.StickerSet_ec0b3f33d3

/** Metadata-only MTProto sticker reads; file paths and mutations require media projections. */
internal class MtProtoStickerRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val locations: MtProtoDocumentLocationStore,
    private val files: MtProtoFileRepository,
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
            result.stickers.filterIsInstance<Document_be725c3b31>().also { stageDocuments(it) }.mapNotNull { it.toDomain() }
        }

    override suspend fun clearRecentStickers() {
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(ClearRecentStickers(attached = false))) {
                "MTProto recent sticker clearing was rejected"
            }
        }
    }
    override fun getStickerFile(fileId: Long): Flow<String?> = file(fileId)
    override fun getCustomEmojiFile(customEmojiId: Long): Flow<String?> = file(customEmojiId)
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
            result.documents.filterIsInstance<Document_be725c3b31>().also { stageDocuments(it) }
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
            result.stickers.filterIsInstance<Document_be725c3b31>().also { stageDocuments(it) }.mapNotNull { it.toDomain() }
        }
    }
    override suspend fun getStickerEmojiHints(query: String): List<String> = unsupported("sticker emoji hints")
    override suspend fun searchStickerSets(query: String): List<StickerSetModel> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        return transportFactory.open(accountSlot).use { transport ->
            val result = transport.execute(SearchStickerSets(excludeFeatured = false, q = normalized, hash = 0))
                as? FoundStickerSets_215fe0f754
                ?: error("Unsupported MTProto sticker-set search response")
            result.sets.flatMap { it.documents() }.also { stageDocuments(it) }
            result.sets.mapNotNull { it.toDomain() }
        }
    }

    private fun file(documentId: Long): Flow<String?> = flow {
        val file = files.registerDocument(documentId)
        if (file == null) {
            emit(null)
            return@flow
        }
        files.getPath(file.fileId)?.let {
            emit(it)
            return@flow
        }
        val result = coroutineScope {
            val completed = async(start = CoroutineStart.UNDISPATCHED) {
                files.fileDownloadFlow
                    .filter { it.fileId == file.fileId }
                    .first { it is FileDownloadEvent.Completed || it is FileDownloadEvent.Cancelled }
            }
            files.download(file.fileId, offset = 0L, limit = 0L)
            completed.await()
        }
        emit((result as? FileDownloadEvent.Completed)?.path)
    }

    private suspend fun stageDocuments(documents: List<Document_be725c3b31>) {
        if (documents.isEmpty()) return
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        documents.forEach { locations.upsert(scope, it) }
    }

    private fun StickerSetCovered_1af4b31f79.documents(): List<Document_be725c3b31> = when (this) {
        is StickerSetCovered_34353f5c94 -> listOfNotNull(cover as? Document_be725c3b31)
        is StickerSetMultiCovered -> covers.filterIsInstance<Document_be725c3b31>()
        is StickerSetFullCovered -> documents.filterIsInstance<Document_be725c3b31>()
        else -> emptyList()
    }

    private fun StickerSetCovered_1af4b31f79.toDomain(): StickerSetModel? = when (this) {
        is StickerSetCovered_34353f5c94 -> summary(set_, listOf(cover), cover)
        is StickerSetMultiCovered -> summary(set_, emptyList(), covers.firstOrNull())
        is StickerSetFullCovered -> summary(set_, documents, documents.firstOrNull())
        else -> null
    }

    private fun summary(
        rawSet: org.monogram.mtproto.tl.generated.cloud.layer223.StickerSet_e88393a32f,
        rawStickers: List<org.monogram.mtproto.tl.generated.cloud.layer223.Document_323aaa1d96>,
        rawThumbnail: org.monogram.mtproto.tl.generated.cloud.layer223.Document_323aaa1d96?,
    ): StickerSetModel? {
        val set = rawSet as? org.monogram.mtproto.tl.generated.cloud.layer223.StickerSet_97ab856701 ?: return null
        return StickerSetModel(
            id = set.id,
            title = set.title,
            name = set.shortName,
            stickers = rawStickers.mapNotNull { (it as? Document_be725c3b31)?.toDomain() },
            thumbnail = (rawThumbnail as? Document_be725c3b31)?.toDomain(),
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
