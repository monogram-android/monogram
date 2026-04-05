package org.monogram.data.repository

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.data.datasource.remote.StickerRemoteSource
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.stickers.StickerFileManager
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.models.StickerType
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.StickerRepository

class StickerRepositoryImpl(
    private val remote: StickerRemoteSource,
    private val fileManager: StickerFileManager,
    private val updates: UpdateDispatcher,
    private val cacheProvider: CacheProvider,
    private val dispatchers: DispatcherProvider,
    private val localDataSource: StickerLocalDataSource,
    scopeProvider: ScopeProvider
) : StickerRepository {

    private val scope = scopeProvider.appScope

    override val installedStickerSets: StateFlow<List<StickerSetModel>> = cacheProvider.installedStickerSets
    override val customEmojiStickerSets: StateFlow<List<StickerSetModel>> = cacheProvider.customEmojiStickerSets

    private val _archivedStickerSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    override val archivedStickerSets: StateFlow<List<StickerSetModel>> = _archivedStickerSets.asStateFlow()

    private val _archivedEmojiSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    override val archivedEmojiSets: StateFlow<List<StickerSetModel>> = _archivedEmojiSets.asStateFlow()

    private val regularMutex = Mutex()
    private val customEmojiMutex = Mutex()
    private val archivedMutex = Mutex()
    private val archivedEmojiMutex = Mutex()

    @Volatile
    private var lastRegularLoadTime = 0L

    @Volatile
    private var lastCustomEmojiLoadTime = 0L

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
            localDataSource.getInstalledStickerSetsByType(REGULAR_TYPE).collect { sets ->
                if (installedStickerSets.value.isEmpty() && sets.isNotEmpty()) {
                    cacheProvider.setInstalledStickerSets(sets)
                }
            }
        }

        scope.launch {
            localDataSource.getInstalledStickerSetsByType(CUSTOM_EMOJI_TYPE).collect { sets ->
                if (customEmojiStickerSets.value.isEmpty() && sets.isNotEmpty()) {
                    cacheProvider.setCustomEmojiStickerSets(sets)
                }
            }
        }

        scope.launch(dispatchers.default) {
            delay(VERIFY_INITIAL_DELAY_MS)
            while (isActive) {
                coRunCatching {
                    fileManager.verifyInstalledStickerSets(installedStickerSets.value + customEmojiStickerSets.value)
                }.onFailure { Log.w(TAG, "verifyInstalledStickerSets failed", it) }
                delay(VERIFY_INTERVAL_MS)
            }
        }
    }

    override suspend fun loadInstalledStickerSets() = loadInstalledStickerSets(force = false)

    private suspend fun loadInstalledStickerSets(force: Boolean) = regularMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && installedStickerSets.value.isNotEmpty()) return@withLock
        if (force && installedStickerSets.value.isNotEmpty() && now - lastRegularLoadTime < LOAD_DEBOUNCE_MS) {
            return@withLock
        }

        val sets = remote.getInstalledStickerSets(StickerType.REGULAR)
        if (force && installedStickerSets.value.map { it.id } == sets.map { it.id }) {
            lastRegularLoadTime = System.currentTimeMillis()
            return@withLock
        }

        cacheProvider.setInstalledStickerSets(sets)
        localDataSource.saveStickerSets(sets, REGULAR_TYPE, isInstalled = true, isArchived = false)
        lastRegularLoadTime = System.currentTimeMillis()
    }

    override suspend fun loadCustomEmojiStickerSets() = loadCustomEmojiStickerSets(force = false)

    private suspend fun loadCustomEmojiStickerSets(force: Boolean) = customEmojiMutex.withLock {
        val now = System.currentTimeMillis()

        if (!force && customEmojiStickerSets.value.isNotEmpty()) {
            val hasMissingCustomEmojiIds = customEmojiStickerSets.value.any { set ->
                set.stickerType == StickerType.CUSTOM_EMOJI && set.stickers.any { it.customEmojiId == null }
            }
            val needsRefresh =
                lastCustomEmojiLoadTime == 0L || (now - lastCustomEmojiLoadTime) > CUSTOM_EMOJI_REFRESH_MS
            if (!hasMissingCustomEmojiIds && !needsRefresh) return@withLock
        }
        if (force && customEmojiStickerSets.value.isNotEmpty() && now - lastCustomEmojiLoadTime < LOAD_DEBOUNCE_MS) {
            return@withLock
        }

        val sets = remote.getInstalledStickerSets(StickerType.CUSTOM_EMOJI)
        if (sets.isNotEmpty()) {
            if (force && customEmojiStickerSets.value.map { it.id } == sets.map { it.id }) {
                lastCustomEmojiLoadTime = System.currentTimeMillis()
                return@withLock
            }

            cacheProvider.setCustomEmojiStickerSets(sets)
            localDataSource.saveStickerSets(sets, CUSTOM_EMOJI_TYPE, isInstalled = true, isArchived = false)
            lastCustomEmojiLoadTime = System.currentTimeMillis()
            return@withLock
        }

        if (customEmojiStickerSets.value.isEmpty()) {
            val cached = localDataSource.getInstalledStickerSetsByType(CUSTOM_EMOJI_TYPE).first()
            if (cached.isNotEmpty()) {
                cacheProvider.setCustomEmojiStickerSets(cached)
            }
        }
        lastCustomEmojiLoadTime = System.currentTimeMillis()
    }

    override suspend fun loadArchivedStickerSets() = archivedMutex.withLock {
        val cached = localDataSource.getArchivedStickerSetsByType(REGULAR_TYPE).first()
        if (cached.isNotEmpty()) {
            _archivedStickerSets.value = cached
        }

        val remoteSets = remote.getArchivedStickerSets(StickerType.REGULAR)
        _archivedStickerSets.value = remoteSets
        localDataSource.saveStickerSets(remoteSets, REGULAR_TYPE, isInstalled = false, isArchived = true)
    }

    override suspend fun loadArchivedEmojiSets() = archivedEmojiMutex.withLock {
        val cached = localDataSource.getArchivedStickerSetsByType(CUSTOM_EMOJI_TYPE).first()
        if (cached.isNotEmpty()) {
            _archivedEmojiSets.value = cached
        }

        val remoteSets = remote.getArchivedStickerSets(StickerType.CUSTOM_EMOJI)
        _archivedEmojiSets.value = remoteSets
        localDataSource.saveStickerSets(remoteSets, CUSTOM_EMOJI_TYPE, isInstalled = false, isArchived = true)
    }

    override suspend fun getStickerSet(setId: Long): StickerSetModel? {
        val cached = localDataSource.getStickerSetById(setId)
        if (cached != null) {
            onStickerSetResolved(cached)
            return cached
        }

        val remoteSet = remote.getStickerSet(setId) ?: return null
        localDataSource.insertStickerSet(remoteSet, remoteSet.stickerType.name)
        onStickerSetResolved(remoteSet)
        return remoteSet
    }

    override suspend fun getStickerSetByName(name: String): StickerSetModel? {
        val cached = localDataSource.getStickerSetByName(name)
        if (cached != null) {
            onStickerSetResolved(cached)
            return cached
        }

        val remoteSet = remote.getStickerSetByName(name) ?: return null
        localDataSource.insertStickerSet(remoteSet, remoteSet.stickerType.name)
        onStickerSetResolved(remoteSet)
        return remoteSet
    }

    override suspend fun verifyStickerSet(setId: Long) {
        val set = localDataSource.getStickerSetById(setId) ?: remote.getStickerSet(setId) ?: return
        fileManager.verifyStickerSet(set)
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

    override suspend fun getRecentStickers(): List<StickerModel> {
        return remote.getRecentStickers()
    }

    override suspend fun clearRecentStickers() {
        remote.clearRecentStickers()
    }

    override suspend fun searchStickers(query: String): List<StickerModel> {
        return remote.searchStickers(query)
    }

    override suspend fun searchStickerSets(query: String): List<StickerSetModel> {
        return remote.searchStickerSets(query)
    }

    override fun getStickerFile(fileId: Long): Flow<String?> {
        return fileManager.getStickerFile(fileId)
    }

    override suspend fun getTgsJson(path: String): String? {
        return fileManager.getTgsJson(path)
    }

    override fun clearCache() {
        fileManager.clearCache()
        invalidateStickerSetCaches()
    }

    private fun onStickerSetResolved(set: StickerSetModel) {
        fileManager.prefetchStickers(set.stickers)
        scope.launch(dispatchers.default) {
            fileManager.verifyStickerSet(set)
        }
    }

    private fun invalidateStickerSetCaches() {
        cacheProvider.setInstalledStickerSets(emptyList())
        cacheProvider.setCustomEmojiStickerSets(emptyList())
        _archivedStickerSets.value = emptyList()
        _archivedEmojiSets.value = emptyList()
        lastRegularLoadTime = 0
        lastCustomEmojiLoadTime = 0
        scope.launch {
            localDataSource.clearStickerSets()
        }
    }

    companion object {
        private const val TAG = "StickerRepository"
        private const val REGULAR_TYPE = "REGULAR"
        private const val CUSTOM_EMOJI_TYPE = "CUSTOM_EMOJI"
        private const val LOAD_DEBOUNCE_MS = 1_000L
        private const val CUSTOM_EMOJI_REFRESH_MS = 10 * 60 * 1_000L
        private const val VERIFY_INITIAL_DELAY_MS = 60_000L
        private const val VERIFY_INTERVAL_MS = 120_000L
    }
}