package org.monogram.data.repository

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.db.dao.WallpaperDao
import org.monogram.data.db.model.WallpaperEntity
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.mapper.mapBackgrounds
import org.monogram.domain.models.WallpaperModel
import org.monogram.domain.repository.WallpaperRepository

class WallpaperRepositoryImpl(
    private val remote: SettingsRemoteDataSource,
    private val updates: UpdateDispatcher,
    private val wallpaperDao: WallpaperDao,
    private val dispatchers: DispatcherProvider,
    scopeProvider: ScopeProvider
) : WallpaperRepository {

    private val scope = scopeProvider.appScope

    private val wallpaperUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val wallpapers = MutableStateFlow<List<WallpaperModel>>(emptyList())

    init {
        scope.launch {
            updates.file.collect {
                wallpaperUpdates.emit(Unit)
            }
        }

        scope.launch {
            wallpaperDao.getWallpapers().collect { entities ->
                val models = entities.mapNotNull {
                    try {
                        Json.decodeFromString<WallpaperModel>(it.data)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (models.isNotEmpty()) {
                    wallpapers.value = models
                }
            }
        }
    }

    override fun getWallpapers() = callbackFlow {
        suspend fun fetch() {
            val result = remote.getInstalledBackgrounds(false)
            val mappedWallpapers = mapBackgrounds(result?.backgrounds ?: emptyArray())
            wallpapers.value = mappedWallpapers
            saveWallpapersToDb(mappedWallpapers)
            trySend(mappedWallpapers)
        }

        val wallpaperJob = wallpaperUpdates
            .onEach { fetch() }
            .launchIn(this)

        if (wallpapers.value.isNotEmpty()) {
            trySend(wallpapers.value)
        } else {
            fetch()
        }

        awaitClose { wallpaperJob.cancel() }
    }

    override suspend fun downloadWallpaper(fileId: Int) {
        remote.downloadFile(fileId, 1)
    }

    private suspend fun saveWallpapersToDb(wallpapers: List<WallpaperModel>) {
        withContext(dispatchers.io) {
            wallpaperDao.clearAll()
            wallpaperDao.insertWallpapers(
                wallpapers.map {
                    WallpaperEntity(it.id, Json.encodeToString(it))
                }
            )
        }
    }
}