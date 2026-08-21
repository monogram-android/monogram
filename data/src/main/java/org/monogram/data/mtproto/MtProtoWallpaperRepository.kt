package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.WallpaperModel
import org.monogram.domain.models.WallpaperSettings
import org.monogram.domain.models.WallpaperType
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.WallPaperNoFile
import org.monogram.mtproto.tl.generated.cloud.layer223.WallPaperSettings_2cd7142740
import org.monogram.mtproto.tl.generated.cloud.layer223.WallPaper_7e3ce0b613
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetWallPapers
import org.monogram.mtproto.tl.generated.cloud.layer223.account.WallPapers_1d62475ab5

/** Reads installed wallpapers and delegates their opaque file handles to the owned downloader. */
internal interface MtProtoWallpaperRepository {
    fun wallpapers(): Flow<List<WallpaperModel>>
    fun download(fileId: Int)
}

internal class MtProtoWallpaperRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val documents: MtProtoDocumentLocationStore,
    private val files: MtProtoFileRepository,
    private val scope: CoroutineScope,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoWallpaperRepository {
    private val installedWallpapers = MutableStateFlow<List<WallpaperModel>>(emptyList())
    private var refreshStarted = false

    init {
        scope.launch {
            files.fileDownloadFlow.collect { event ->
                if (event is FileDownloadEvent.Completed) {
                    installedWallpapers.value = installedWallpapers.value.map { wallpaper ->
                        if (wallpaper.documentId == event.fileId.toLong()) {
                            wallpaper.copy(isDownloaded = true, localPath = event.path)
                        } else {
                            wallpaper
                        }
                    }
                }
            }
        }
    }

    override fun wallpapers(): Flow<List<WallpaperModel>> = installedWallpapers.onStart {
        if (!refreshStarted) {
            refreshStarted = true
            scope.launch { refresh() }
        }
    }

    override fun download(fileId: Int) {
        files.download(fileId, offset = 0L, limit = 0L)
    }

    private suspend fun refresh() {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val result = transportFactory.open(accountSlot).use { transport ->
            transport.execute(GetWallPapers(hash = 0L))
        }
        val wallpapers = (result as? WallPapers_1d62475ab5)?.wallpapers.orEmpty()
        installedWallpapers.value = wallpapers.mapNotNull { wallpaper -> wallpaper.toModel(scope) }
    }

    private suspend fun org.monogram.mtproto.tl.generated.cloud.layer223.WallPaper_3e7f6e776a.toModel(
        scope: MtProtoAuthKeyScope,
    ): WallpaperModel? = when (this) {
        is WallPaper_7e3ce0b613 -> {
            val document = document as? Document_be725c3b31 ?: return null
            documents.upsert(scope, document)
            val file = files.registerDocument(document.id) ?: return null
            val path = files.getPath(file.fileId)
            WallpaperModel(
                id = id,
                slug = slug,
                title = slug,
                type = if (pattern) WallpaperType.PATTERN else WallpaperType.WALLPAPER,
                pattern = pattern,
                documentId = file.fileId.toLong(),
                thumbnail = null,
                settings = settings.toModel(),
                isDownloaded = path != null,
                localPath = path,
                isDefault = default,
            )
        }

        is WallPaperNoFile -> WallpaperModel(
            id = id,
            slug = "",
            title = "",
            type = WallpaperType.FILL,
            pattern = false,
            documentId = 0L,
            thumbnail = null,
            settings = settings.toModel(),
            isDownloaded = true,
            localPath = null,
            isDefault = default,
        )
    }

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.WallPaperSettings_8b154fe33d?.toModel(): WallpaperSettings? =
        (this as? WallPaperSettings_2cd7142740)?.let { settings ->
            WallpaperSettings(
                backgroundColor = settings.backgroundColor,
                secondBackgroundColor = settings.secondBackgroundColor,
                thirdBackgroundColor = settings.thirdBackgroundColor,
                fourthBackgroundColor = settings.fourthBackgroundColor,
                intensity = settings.intensity,
                rotation = settings.rotation,
                isMoving = settings.motion,
                isBlurred = settings.blur,
            )
        }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
