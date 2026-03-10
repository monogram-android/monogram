package org.monogram.data.repository

import android.content.Context
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.StickerRemoteSource
import org.monogram.data.db.dao.RecentEmojiDao
import org.monogram.data.db.dao.StickerPathDao
import org.monogram.data.db.dao.StickerSetDao
import org.monogram.data.db.model.RecentEmojiEntity
import org.monogram.data.db.model.StickerPathEntity
import org.monogram.data.db.model.StickerSetEntity
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.infra.EmojiLoader
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.FileUpdateHandler
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.models.StickerType
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.StickerRepository
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

class StickerRepositoryImpl(
    private val remote: StickerRemoteSource,
    private val fileQueue: FileDownloadQueue,
    private val fileUpdateHandler: FileUpdateHandler,
    private val updates: UpdateDispatcher,
    private val cacheProvider: CacheProvider,
    private val dispatchers: DispatcherProvider,
    private val context: Context,
    private val stickerSetDao: StickerSetDao,
    private val recentEmojiDao: RecentEmojiDao,
    private val stickerPathDao: StickerPathDao,
    scopeProvider: ScopeProvider
) : StickerRepository {

    private val scope = scopeProvider.appScope

    override val installedStickerSets: StateFlow<List<StickerSetModel>> = cacheProvider.installedStickerSets
    override val customEmojiStickerSets: StateFlow<List<StickerSetModel>> = cacheProvider.customEmojiStickerSets

    private val _archivedStickerSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    override val archivedStickerSets = _archivedStickerSets.asStateFlow()

    private val _archivedEmojiSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    override val archivedEmojiSets = _archivedEmojiSets.asStateFlow()

    private val regularMutex = Mutex()
    private val customEmojiMutex = Mutex()
    private val archivedMutex = Mutex()
    private val archivedEmojiMutex = Mutex()

    @Volatile
    private var lastRegularLoadTime = 0L
    @Volatile
    private var lastCustomEmojiLoadTime = 0L

    override val recentEmojis: Flow<List<RecentEmojiModel>> = cacheProvider.recentEmojis

    private val tgsCache = mutableMapOf<String, String>()
    private val filePathsCache = ConcurrentHashMap<Long, String>()
    private var cachedEmojis: List<String>? = null
    private var fallbackEmojisCache: List<String>? = null

    init {
        scope.launch {
            updates.installedStickerSets.collect { update ->
                when (update.stickerType) {
                    is TdApi.StickerTypeRegular -> loadInstalledStickerSets(force = true)
                    is TdApi.StickerTypeCustomEmoji -> loadCustomEmojiStickerSets(force = true)
                }
            }
        }

        scope.launch {
            stickerSetDao.getInstalledStickerSetsByType("REGULAR").collect { entities ->
                if (installedStickerSets.value.isEmpty()) {
                    val sets = entities.mapNotNull { it.toModel() }
                    if (sets.isNotEmpty()) cacheProvider.setInstalledStickerSets(sets)
                }
            }
        }

        scope.launch {
            stickerSetDao.getInstalledStickerSetsByType("CUSTOM_EMOJI").collect { entities ->
                if (customEmojiStickerSets.value.isEmpty()) {
                    val sets = entities.mapNotNull { it.toModel() }
                    if (sets.isNotEmpty()) cacheProvider.setCustomEmojiStickerSets(sets)
                }
            }
        }

        scope.launch {
            recentEmojiDao.getRecentEmojis().collect { entities ->
                val models = entities.mapNotNull {
                    try {
                        Json.decodeFromString<RecentEmojiModel>(it.data)
                    } catch (e: Exception) {
                        null
                    }
                }
                cacheProvider.setRecentEmojis(models)
            }
        }
    }

    override suspend fun loadInstalledStickerSets() = loadInstalledStickerSets(force = false)

    private suspend fun loadInstalledStickerSets(force: Boolean) = regularMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && installedStickerSets.value.isNotEmpty()) return@withLock
        if (force && installedStickerSets.value.isNotEmpty() && now - lastRegularLoadTime < 1000) return@withLock

        val sets = remote.getInstalledStickerSets(StickerType.REGULAR)
        if (force && installedStickerSets.value.map { it.id } == sets.map { it.id }) {
            lastRegularLoadTime = System.currentTimeMillis()
            return@withLock
        }

        cacheProvider.setInstalledStickerSets(sets)
        saveStickerSetsToDb(sets, "REGULAR", isInstalled = true, isArchived = false)
        lastRegularLoadTime = System.currentTimeMillis()
    }

    override suspend fun loadCustomEmojiStickerSets() = loadCustomEmojiStickerSets(force = false)

    private suspend fun loadCustomEmojiStickerSets(force: Boolean) = customEmojiMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && customEmojiStickerSets.value.isNotEmpty()) return@withLock
        if (force && customEmojiStickerSets.value.isNotEmpty() && now - lastCustomEmojiLoadTime < 1000) return@withLock

        val sets = remote.getInstalledStickerSets(StickerType.CUSTOM_EMOJI)
        if (force && customEmojiStickerSets.value.map { it.id } == sets.map { it.id }) {
            lastCustomEmojiLoadTime = System.currentTimeMillis()
            return@withLock
        }

        cacheProvider.setCustomEmojiStickerSets(sets)
        saveStickerSetsToDb(sets, "CUSTOM_EMOJI", isInstalled = true, isArchived = false)
        lastCustomEmojiLoadTime = System.currentTimeMillis()
    }

    private suspend fun saveStickerSetsToDb(
        sets: List<StickerSetModel>,
        type: String,
        isInstalled: Boolean,
        isArchived: Boolean
    ) {
        withContext(dispatchers.io) {
            stickerSetDao.deleteStickerSets(type, isInstalled, isArchived)
            stickerSetDao.insertStickerSets(sets.map { it.toEntity(type) })
        }
    }

    override suspend fun loadArchivedStickerSets() = archivedMutex.withLock {
        val cached = stickerSetDao.getArchivedStickerSetsByType("REGULAR").first().mapNotNull { it.toModel() }
        if (cached.isNotEmpty()) _archivedStickerSets.value = cached

        val remoteSets = remote.getArchivedStickerSets(StickerType.REGULAR)
        _archivedStickerSets.value = remoteSets
        saveStickerSetsToDb(remoteSets, "REGULAR", isInstalled = false, isArchived = true)
    }

    override suspend fun loadArchivedEmojiSets() = archivedEmojiMutex.withLock {
        val cached = stickerSetDao.getArchivedStickerSetsByType("CUSTOM_EMOJI").first().mapNotNull { it.toModel() }
        if (cached.isNotEmpty()) _archivedEmojiSets.value = cached

        val remoteSets = remote.getArchivedStickerSets(StickerType.CUSTOM_EMOJI)
        _archivedEmojiSets.value = remoteSets
        saveStickerSetsToDb(remoteSets, "CUSTOM_EMOJI", isInstalled = false, isArchived = true)
    }

    override suspend fun getStickerSet(setId: Long): StickerSetModel? {
        val cached = stickerSetDao.getStickerSetById(setId)?.toModel()
        if (cached != null) return cached

        val remoteSet = remote.getStickerSet(setId) ?: return null
        withContext(dispatchers.io) {
            stickerSetDao.insertStickerSet(remoteSet.toEntity(remoteSet.stickerType.name))
        }
        return remoteSet
    }

    override suspend fun getStickerSetByName(name: String): StickerSetModel? {
        val cached = stickerSetDao.getStickerSetByName(name)?.toModel()
        if (cached != null) return cached

        val remoteSet = remote.getStickerSetByName(name) ?: return null
        withContext(dispatchers.io) {
            stickerSetDao.insertStickerSet(remoteSet.toEntity(remoteSet.stickerType.name))
        }
        return remoteSet
    }

    override suspend fun toggleStickerSetInstalled(setId: Long, isInstalled: Boolean) {
        remote.toggleStickerSetInstalled(setId, isInstalled)
        invalidateStickerSetCaches()
    }

    override suspend fun toggleStickerSetArchived(setId: Long, isArchived: Boolean) {
        remote.toggleStickerSetArchived(setId, isArchived)
        invalidateStickerSetCaches()
        scope.launch {
            loadArchivedStickerSets()
            loadArchivedEmojiSets()
        }
    }

    override suspend fun reorderStickerSets(
        stickerType: StickerRepository.TdLibStickerType,
        stickerSetIds: List<Long>
    ) {
        val type = when (stickerType) {
            StickerRepository.TdLibStickerType.REGULAR -> StickerType.REGULAR
            StickerRepository.TdLibStickerType.CUSTOM_EMOJI -> StickerType.CUSTOM_EMOJI
            StickerRepository.TdLibStickerType.MASK -> StickerType.MASK
        }
        remote.reorderStickerSets(type, stickerSetIds)
    }

    override suspend fun getDefaultEmojis(): List<String> {
        cachedEmojis?.let { return it }

        val fetched = remote.getEmojiCategories().toMutableSet()
        if (fetched.size < 100) fetched.addAll(getFallbackEmojis())

        return fetched.toList().also { cachedEmojis = it }
    }

    override suspend fun searchEmojis(query: String) = remote.searchEmojis(query)

    override suspend fun searchCustomEmojis(query: String) = remote.searchCustomEmojis(query)

    override suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long) =
        remote.getMessageAvailableReactions(chatId, messageId)

    override suspend fun getRecentStickers() = remote.getRecentStickers()

    override suspend fun clearRecentStickers() = remote.clearRecentStickers()

    override suspend fun searchStickers(query: String) = remote.searchStickers(query)

    override suspend fun searchStickerSets(query: String) = remote.searchStickerSets(query)

    override suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel) {
        cacheProvider.addRecentEmoji(recentEmoji)
        withContext(dispatchers.io) {
            recentEmojiDao.deleteRecentEmoji(recentEmoji.emoji, recentEmoji.sticker?.id)
            recentEmojiDao.insertRecentEmoji(
                RecentEmojiEntity(
                    emoji = recentEmoji.emoji,
                    stickerId = recentEmoji.sticker?.id,
                    data = Json.encodeToString(recentEmoji)
                )
            )
        }
    }

    override suspend fun clearRecentEmojis() {
        cacheProvider.clearRecentEmojis()
        withContext(dispatchers.io) {
            recentEmojiDao.clearAll()
        }
    }

    override suspend fun getSavedGifs(): List<GifModel> {
        val cached = cacheProvider.savedGifs.value
        if (cached.isNotEmpty()) return cached

        val remoteGifs = remote.getSavedGifs()
        cacheProvider.setSavedGifs(remoteGifs)
        return remoteGifs
    }

    override suspend fun addSavedGif(path: String) {
        remote.addSavedGif(path)
        val remoteGifs = remote.getSavedGifs()
        cacheProvider.setSavedGifs(remoteGifs)
    }

    override suspend fun searchGifs(query: String) = remote.searchGifs(query)

    override fun getStickerFile(fileId: Long): Flow<String?> = channelFlow {
        filePathsCache[fileId]?.let { send(it); return@channelFlow }

        val dbPath = stickerPathDao.getPath(fileId)
        if (dbPath != null) {
            filePathsCache[fileId] = dbPath
            send(dbPath)
            return@channelFlow
        }

        val job = launch {
            fileUpdateHandler.downloadCompleted
                .filter { it.first == fileId }
                .collect { (_, path) ->
                    filePathsCache[fileId] = path
                    stickerPathDao.insertPath(StickerPathEntity(fileId, path))
                    send(path)
                }
        }

        val cachedPath = fileUpdateHandler.downloadCompleted
            .replayCache
            .firstOrNull { it.first == fileId }
            ?.second

        if (cachedPath != null) {
            filePathsCache[fileId] = cachedPath
            stickerPathDao.insertPath(StickerPathEntity(fileId, cachedPath))
            send(cachedPath)
            job.cancel()
            return@channelFlow
        }

        fileQueue.enqueue(fileId.toInt(), 32, FileDownloadQueue.DownloadType.STICKER)
        awaitClose { job.cancel() }
    }

    override fun getGifFile(gif: GifModel): Flow<String?> = flow {
        if (gif.fileId == 0L) {
            emit(null); return@flow
        }
        getStickerFile(gif.fileId).collect { emit(it) }
    }

    override suspend fun getTgsJson(path: String): String? = withContext(dispatchers.io) {
        tgsCache[path]?.let { return@withContext it }
        runCatching {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return@withContext null
            GZIPInputStream(FileInputStream(file))
                .bufferedReader()
                .use { it.readText() }
                .also { tgsCache[path] = it }
        }.getOrNull()
    }

    override fun clearCache() {
        tgsCache.clear()
        filePathsCache.clear()
        cachedEmojis = null
        fallbackEmojisCache = null
        invalidateStickerSetCaches()
        scope.launch {
            stickerPathDao.clearAll()
        }
    }

    private fun invalidateStickerSetCaches() {
        cacheProvider.setInstalledStickerSets(emptyList())
        cacheProvider.setCustomEmojiStickerSets(emptyList())
        lastRegularLoadTime = 0
        lastCustomEmojiLoadTime = 0
        scope.launch {
            stickerSetDao.clearAll()
        }
    }

    private suspend fun getFallbackEmojis(): List<String> = withContext(dispatchers.default) {
        fallbackEmojisCache?.let { return@withContext it }
        EmojiLoader.getSupportedEmojis(context).also { fallbackEmojisCache = it }
    }

    private fun StickerSetModel.toEntity(type: String) = StickerSetEntity(
        id = id,
        name = name,
        type = type,
        isInstalled = isInstalled,
        isArchived = isArchived,
        data = Json.encodeToString(this)
    )

    private fun StickerSetEntity.toModel(): StickerSetModel? = try {
        Json.decodeFromString<StickerSetModel>(data)
    } catch (e: Exception) {
        null
    }
}