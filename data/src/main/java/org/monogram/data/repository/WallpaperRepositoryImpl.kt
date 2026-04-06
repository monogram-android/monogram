package org.monogram.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.db.dao.WallpaperDao
import org.monogram.data.db.model.WallpaperEntity
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.mapper.mapBackgrounds
import org.monogram.data.mapper.toBackgroundType
import org.monogram.data.mapper.toDomain
import org.monogram.data.mapper.toInputBackground
import org.monogram.domain.models.WallpaperModel
import org.monogram.domain.repository.WallpaperRepository

class WallpaperRepositoryImpl(
    private val remote: SettingsRemoteDataSource,
    private val updates: UpdateDispatcher,
    private val wallpaperDao: WallpaperDao,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope
) : WallpaperRepository {

    private val wallpaperUpdates = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val wallpapers = MutableStateFlow<List<WallpaperModel>>(emptyList())

    init {
        scope.launch {
            updates.file.collectLatest {
                wallpaperUpdates.tryEmit(Unit)
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

    override suspend fun setDefaultWallpaper(
        wallpaper: WallpaperModel,
        isBlurred: Boolean,
        isMoving: Boolean
    ): WallpaperModel? {
        val background = wallpaper.toInputBackground()
        val type = wallpaper.toBackgroundType(isBlurred = isBlurred, isMoving = isMoving)
        val result = remote.setDefaultBackground(
            background = background,
            type = type,
            forDarkTheme = false
        ) ?: return null

        wallpaperUpdates.emit(Unit)
        return result.toDomain()
    }

    override suspend fun uploadWallpaper(
        filePath: String,
        isBlurred: Boolean,
        isMoving: Boolean
    ): WallpaperModel? {
        val result = remote.setDefaultBackground(
            background = TdApi.InputBackgroundLocal(TdApi.InputFileLocal(filePath)),
            type = TdApi.BackgroundTypeWallpaper(isBlurred, isMoving),
            forDarkTheme = false
        ) ?: return null

        wallpaperUpdates.emit(Unit)
        return result.toDomain()
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