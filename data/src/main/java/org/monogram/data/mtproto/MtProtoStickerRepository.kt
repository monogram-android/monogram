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
import org.monogram.mtproto.tl.generated.cloud.layer223.EmojiKeyword_0d35930ff3
import org.monogram.mtproto.tl.generated.cloud.layer223.EmojiKeywordsDifference_ce8b93b74e
import org.monogram.mtproto.tl.generated.cloud.layer223.StickerSet_97ab856701
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeCustomEmoji
import org.monogram.mtproto.tl.generated.cloud.layer223.StickerSetCovered_1af4b31f79
import org.monogram.mtproto.tl.generated.cloud.layer223.StickerSetCovered_34353f5c94
import org.monogram.mtproto.tl.generated.cloud.layer223.StickerSetFullCovered
import org.monogram.mtproto.tl.generated.cloud.layer223.StickerSetMultiCovered
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeImageSize
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeSticker
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeVideo
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.InputStickerSetId
import org.monogram.mtproto.tl.generated.cloud.layer223.InputStickerSetShortName
import org.monogram.mtproto.tl.generated.cloud.layer223.InputStickerSet
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.AllStickers_638a4b63d6
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ArchivedStickers_8455cc1f39
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ClearRecentStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetAllStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetArchivedStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetRecentStickers
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetStickerSet
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetEmojiKeywords
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.InstallStickerSet
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ReorderStickerSets
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.StickerSetInstallResultArchive
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.StickerSetInstallResultSuccess
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.UninstallStickerSet
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
    private val installedSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    private val customEmojiSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    private val archivedSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    private val archivedEmojis = MutableStateFlow<List<StickerSetModel>>(emptyList())
    private val accessHashes = mutableMapOf<Long, Long>()
    private var emojiKeywords: List<EmojiKeyword_0d35930ff3>? = null

    override val installedStickerSets: StateFlow<List<StickerSetModel>> = installedSets
    override val customEmojiStickerSets: StateFlow<List<StickerSetModel>> = customEmojiSets
    override val archivedStickerSets: StateFlow<List<StickerSetModel>> = archivedSets
    override val archivedEmojiSets: StateFlow<List<StickerSetModel>> = archivedEmojis

    override suspend fun loadInstalledStickerSets() {
        installedSets.value = loadInstalled { !it.masks && !it.emojis }
    }

    override suspend fun loadCustomEmojiStickerSets() {
        customEmojiSets.value = loadInstalled { it.emojis }
    }
    override suspend fun loadArchivedStickerSets() {
        archivedSets.value = loadArchived(emojis = false)
    }
    override suspend fun loadArchivedEmojiSets() {
        archivedEmojis.value = loadArchived(emojis = true)
    }
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

    override suspend fun getStickerSet(setId: Long): StickerSetModel? {
        val accessHash = accessHashes[setId]
            ?: throw IllegalStateException(
                "MTProto sticker-set lookup requires the access hash of a previously seen set (id=$setId)",
            )
        return getStickerSet(InputStickerSetId(setId, accessHash))
    }

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
            (result.set_ as? StickerSet_97ab856701)?.let { accessHashes[it.id] = it.accessHash }
            result.documents.filterIsInstance<Document_be725c3b31>().also { stageDocuments(it) }
            return result.toDomain()
        } finally {
            transport.close()
        }
    }

    override suspend fun verifyStickerSet(setId: Long) = throw UnsupportedOperationException(
        "MTProto sticker-set verification is unavailable: no cloud-layer equivalent of Telegram viewStickerSet",
    )

    override suspend fun toggleStickerSetInstalled(setId: Long, isInstalled: Boolean) {
        val input = resolveInputSet(setId)
        transportFactory.open(accountSlot).use { transport ->
            if (isInstalled) {
                when (val result = transport.execute(InstallStickerSet(input, archived = false))) {
                    StickerSetInstallResultSuccess, is StickerSetInstallResultArchive -> Unit
                }
            } else {
                check(transport.execute(UninstallStickerSet(input))) {
                    "MTProto sticker-set uninstall was rejected"
                }
            }
        }
        refreshSetFlows()
    }

    override suspend fun toggleStickerSetArchived(setId: Long, isArchived: Boolean) {
        val input = resolveInputSet(setId)
        transportFactory.open(accountSlot).use { transport ->
            // Archiving moves a set out of the installed list, so uninstall first.
            check(transport.execute(UninstallStickerSet(input))) {
                "MTProto sticker-set uninstall before archive change was rejected"
            }
            when (val result = transport.execute(InstallStickerSet(input, archived = isArchived))) {
                StickerSetInstallResultSuccess, is StickerSetInstallResultArchive -> Unit
            }
        }
        refreshSetFlows()
    }

    override suspend fun reorderStickerSets(stickerType: StickerRepository.StickerSetType, stickerSetIds: List<Long>) {
        if (stickerSetIds.isEmpty()) return
        transportFactory.open(accountSlot).use { transport ->
            check(
                transport.execute(
                    ReorderStickerSets(
                        masks = stickerType == StickerRepository.StickerSetType.MASK,
                        emojis = stickerType == StickerRepository.StickerSetType.CUSTOM_EMOJI,
                        order = stickerSetIds,
                    ),
                ),
            ) {
                "MTProto sticker-set reordering was rejected"
            }
        }
        refreshSetFlows()
    }
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
    override suspend fun getStickerEmojiHints(query: String): List<String> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        val keywords = emojiKeywords ?: fetchEmojiKeywords().also { emojiKeywords = it }
        return keywords
            .filter { keyword ->
                normalized in keyword.keyword.lowercase() || keyword.keyword.lowercase() in normalized
            }
            .flatMap(EmojiKeyword_0d35930ff3::emoticons)
            .distinct()
            .take(EMOJI_HINT_LIMIT)
    }
    override suspend fun searchStickerSets(query: String): List<StickerSetModel> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        return transportFactory.open(accountSlot).use { transport ->
            val result = transport.execute(SearchStickerSets(excludeFeatured = false, q = normalized, hash = 0))
                as? FoundStickerSets_215fe0f754
                ?: error("Unsupported MTProto sticker-set search response")
            result.sets.flatMap { it.documents() }.also { stageDocuments(it) }
            result.sets.forEach { covered -> covered.rawSet()?.let { accessHashes[it.id] = it.accessHash } }
            result.sets.mapNotNull { it.toDomain() }
        }
    }

    private suspend fun loadInstalled(
        include: (org.monogram.mtproto.tl.generated.cloud.layer223.StickerSet_97ab856701) -> Boolean,
    ): List<StickerSetModel> {
        val resolved = transportFactory.open(accountSlot).use { transport ->
            val all = transport.execute(GetAllStickers(0)) as? AllStickers_638a4b63d6
                ?: error("Unsupported MTProto installed stickers response")
            all.sets.filterIsInstance<org.monogram.mtproto.tl.generated.cloud.layer223.StickerSet_97ab856701>()
                .filter(include)
                .map { set ->
                    accessHashes[set.id] = set.accessHash
                    transport.execute(GetStickerSet(InputStickerSetId(set.id, set.accessHash), 0))
                        as? StickerSet_ec0b3f33d3
                        ?: error("Unsupported MTProto installed sticker-set response")
                }
        }
        resolved.flatMap { it.documents.filterIsInstance<Document_be725c3b31>() }.also { stageDocuments(it) }
        return resolved.mapNotNull { it.toDomain() }
    }

    private suspend fun loadArchived(emojis: Boolean): List<StickerSetModel> =
        transportFactory.open(accountSlot).use { transport ->
            val result = transport.execute(GetArchivedStickers(masks = false, emojis = emojis, offsetId = 0, limit = ARCHIVED_LIMIT))
                as? ArchivedStickers_8455cc1f39
                ?: error("Unsupported MTProto archived stickers response")
            result.sets.flatMap { it.documents() }.also { stageDocuments(it) }
            result.sets.forEach { covered -> covered.rawSet()?.let { accessHashes[it.id] = it.accessHash } }
            result.sets.mapNotNull { it.toDomain() }
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

    private suspend fun fetchEmojiKeywords(): List<EmojiKeyword_0d35930ff3> {
        val config = configSource.createForAccount(accountSlot)
        return transportFactory.open(accountSlot).use { transport ->
            (transport.execute(GetEmojiKeywords(config.cloud.systemLanguageCode))
                as? EmojiKeywordsDifference_ce8b93b74e)?.keywords.orEmpty()
                .filterIsInstance<EmojiKeyword_0d35930ff3>()
        }
    }

    private fun resolveInputSet(setId: Long): InputStickerSet {
        val accessHash = accessHashes[setId]
            ?: throw IllegalStateException(
                "MTProto sticker-set mutation requires the access hash of a previously seen set (id=$setId)",
            )
        return InputStickerSetId(setId, accessHash)
    }

    private suspend fun refreshSetFlows() {
        loadInstalledStickerSets()
        loadCustomEmojiStickerSets()
        runCatching { loadArchivedStickerSets() }
    }

    private suspend fun stageDocuments(documents: List<Document_be725c3b31>) {
        if (documents.isEmpty()) return
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        documents.forEach { locations.upsert(scope, it) }
    }

    private fun StickerSetCovered_1af4b31f79.rawSet(): StickerSet_97ab856701? = when (this) {
        is StickerSetCovered_34353f5c94 -> set_ as? StickerSet_97ab856701
        is StickerSetMultiCovered -> set_ as? StickerSet_97ab856701
        is StickerSetFullCovered -> set_ as? StickerSet_97ab856701
        else -> null
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
        const val ARCHIVED_LIMIT = 100
        const val EMOJI_HINT_LIMIT = 20
    }
}
