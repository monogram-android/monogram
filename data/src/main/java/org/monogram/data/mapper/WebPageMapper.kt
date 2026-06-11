package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.TdMessageRemoteDataSource
import org.monogram.domain.models.WebPage
import org.monogram.domain.repository.AppPreferencesProvider

class WebPageMapper(
    private val fileHelper: TdFileHelper,
    private val appPreferences: AppPreferencesProvider
) {
    internal data class PhotoSelection(
        val preferredSize: TdApi.PhotoSize?,
        val thumbnailSize: TdApi.PhotoSize?,
        val originalSize: TdApi.PhotoSize?
    )

    fun map(
        webPage: TdApi.LinkPreview?,
        chatId: Long,
        messageId: Long,
        networkAutoDownload: Boolean
    ): WebPage? {
        if (webPage == null) return null

        var photoObj: TdApi.Photo? = null
        var videoObj: TdApi.Video? = null
        var audioObj: TdApi.Audio? = null
        var documentObj: TdApi.Document? = null
        var stickerObj: TdApi.Sticker? = null
        var animationObj: TdApi.Animation? = null
        var duration = 0

        val linkPreviewType = when (val type = webPage.type) {
            is TdApi.LinkPreviewTypePhoto -> {
                photoObj = type.photo
                WebPage.LinkPreviewType.Photo
            }

            is TdApi.LinkPreviewTypeVideo -> {
                videoObj = type.video
                WebPage.LinkPreviewType.Video
            }

            is TdApi.LinkPreviewTypeAnimation -> {
                animationObj = type.animation
                WebPage.LinkPreviewType.Animation
            }

            is TdApi.LinkPreviewTypeAudio -> {
                audioObj = type.audio
                WebPage.LinkPreviewType.Audio
            }

            is TdApi.LinkPreviewTypeDocument -> {
                documentObj = type.document
                WebPage.LinkPreviewType.Document
            }

            is TdApi.LinkPreviewTypeSticker -> {
                stickerObj = type.sticker
                WebPage.LinkPreviewType.Sticker
            }

            is TdApi.LinkPreviewTypeVideoNote -> {
                WebPage.LinkPreviewType.VideoNote
            }

            is TdApi.LinkPreviewTypeVoiceNote -> {
                WebPage.LinkPreviewType.VoiceNote
            }

            is TdApi.LinkPreviewTypeAlbum -> {
                WebPage.LinkPreviewType.Album
            }

            is TdApi.LinkPreviewTypeArticle -> {
                photoObj = type.photo
                WebPage.LinkPreviewType.Article
            }

            is TdApi.LinkPreviewTypeApp -> {
                WebPage.LinkPreviewType.App
            }

            is TdApi.LinkPreviewTypeExternalVideo -> {
                duration = type.duration
                WebPage.LinkPreviewType.ExternalVideo(type.url)
            }

            is TdApi.LinkPreviewTypeExternalAudio -> {
                duration = type.duration
                WebPage.LinkPreviewType.ExternalAudio(type.url)
            }

            is TdApi.LinkPreviewTypeEmbeddedVideoPlayer -> {
                duration = type.duration
                WebPage.LinkPreviewType.EmbeddedVideo(type.url)
            }

            is TdApi.LinkPreviewTypeEmbeddedAudioPlayer -> {
                duration = type.duration
                WebPage.LinkPreviewType.EmbeddedAudio(type.url)
            }

            is TdApi.LinkPreviewTypeEmbeddedAnimationPlayer -> {
                duration = type.duration
                WebPage.LinkPreviewType.EmbeddedAnimation(type.url)
            }

            is TdApi.LinkPreviewTypeUser -> {
                WebPage.LinkPreviewType.User(0)
            }

            is TdApi.LinkPreviewTypeChat -> {
                WebPage.LinkPreviewType.Chat(0)
            }

            is TdApi.LinkPreviewTypeStory -> {
                WebPage.LinkPreviewType.Story(type.storyPosterChatId, type.storyId)
            }

            is TdApi.LinkPreviewTypeTheme -> {
                WebPage.LinkPreviewType.Theme
            }

            is TdApi.LinkPreviewTypeBackground -> {
                WebPage.LinkPreviewType.Background
            }

            is TdApi.LinkPreviewTypeInvoice -> {
                WebPage.LinkPreviewType.Invoice
            }

            is TdApi.LinkPreviewTypeMessage -> {
                WebPage.LinkPreviewType.Message
            }

            else -> WebPage.LinkPreviewType.Unknown
        }

        fun processTdFile(
            file: TdApi.File,
            downloadType: TdMessageRemoteDataSource.DownloadType,
            supportsStreaming: Boolean = false
        ): TdApi.File {
            val updatedFile = fileHelper.getUpdatedFile(file)
            fileHelper.registerCachedFile(updatedFile.id, chatId, messageId)

            val autoDownload = when (downloadType) {
                TdMessageRemoteDataSource.DownloadType.VIDEO -> supportsStreaming && networkAutoDownload
                TdMessageRemoteDataSource.DownloadType.DEFAULT -> {
                    if (linkPreviewType == WebPage.LinkPreviewType.Document) false else networkAutoDownload
                }

                TdMessageRemoteDataSource.DownloadType.STICKER -> {
                    networkAutoDownload && appPreferences.autoDownloadStickers.value
                }

                TdMessageRemoteDataSource.DownloadType.VIDEO_NOTE -> {
                    networkAutoDownload && appPreferences.autoDownloadVideoNotes.value
                }

                else -> networkAutoDownload
            }

            if (!fileHelper.isValidPath(updatedFile.local.path) && autoDownload) {
                fileHelper.enqueueDownload(updatedFile.id, 1, downloadType, 0, 0, false)
            }

            return updatedFile
        }

        val photo = photoObj?.let { photoObject ->
            val selection = selectPhotoSizes(photoObject.sizes)
            val preferredFile = selection.preferredSize?.photo?.let(fileHelper::getUpdatedFile)
            val thumbnailFile = selection.thumbnailSize?.photo?.let(fileHelper::getUpdatedFile)
            val originalFile = selection.originalSize?.photo?.let(fileHelper::getUpdatedFile)

            if (preferredFile != null) {
                fileHelper.registerCachedFile(preferredFile.id, chatId, messageId)
                if (fileHelper.findBestAvailablePath(
                        preferredFile,
                        photoObject.sizes
                    ) == null && networkAutoDownload
                ) {
                    fileHelper.enqueueDownload(
                        preferredFile.id,
                        1,
                        TdMessageRemoteDataSource.DownloadType.DEFAULT,
                        0,
                        0,
                        false
                    )
                }
            }

            if (thumbnailFile != null) {
                fileHelper.registerCachedFile(thumbnailFile.id, chatId, messageId)
                if (fileHelper.resolveLocalFilePath(thumbnailFile) == null && networkAutoDownload) {
                    fileHelper.enqueueDownload(
                        thumbnailFile.id,
                        1,
                        TdMessageRemoteDataSource.DownloadType.DEFAULT,
                        0,
                        0,
                        false
                    )
                }
            }

            if (originalFile != null && originalFile.id != preferredFile?.id && originalFile.id != thumbnailFile?.id) {
                fileHelper.registerCachedFile(originalFile.id, chatId, messageId)
            }

            selection.preferredSize?.let { preferredSize ->
                WebPage.Photo(
                    path = fileHelper.findBestAvailablePath(preferredFile, photoObject.sizes),
                    thumbnailPath = fileHelper.resolveLocalFilePath(thumbnailFile),
                    width = preferredSize.width,
                    height = preferredSize.height,
                    fileId = preferredFile?.id ?: 0,
                    thumbnailFileId = thumbnailFile?.id ?: 0,
                    originalFileId = originalFile?.id?.takeIf { it != preferredFile?.id } ?: 0,
                    minithumbnail = photoObject.minithumbnail?.data
                )
            }
        }

        val video = videoObj?.let { videoObject ->
            val file = processTdFile(
                videoObject.video,
                TdMessageRemoteDataSource.DownloadType.VIDEO,
                videoObject.supportsStreaming
            )
            WebPage.Video(
                path = fileHelper.resolveLocalFilePath(file),
                width = videoObject.width,
                height = videoObject.height,
                duration = videoObject.duration,
                fileId = file.id,
                thumbnailPath = videoObject.thumbnail?.file?.local?.path?.takeIf {
                    fileHelper.isValidPath(
                        it
                    )
                },
                thumbnailFileId = videoObject.thumbnail?.file?.id ?: 0,
                minithumbnail = videoObject.minithumbnail?.data,
                supportsStreaming = videoObject.supportsStreaming
            )
        }

        val audio = audioObj?.let { audioObject ->
            val file = processTdFile(audioObject.audio, TdMessageRemoteDataSource.DownloadType.DEFAULT)
            WebPage.Audio(
                path = fileHelper.resolveLocalFilePath(file),
                duration = audioObject.duration,
                title = audioObject.title,
                performer = audioObject.performer,
                fileId = file.id
            )
        }

        val document = documentObj?.let { documentObject ->
            val file = processTdFile(documentObject.document, TdMessageRemoteDataSource.DownloadType.DEFAULT)
            WebPage.Document(
                path = fileHelper.resolveLocalFilePath(file),
                fileName = documentObject.fileName,
                mimeType = documentObject.mimeType,
                size = file.size,
                fileId = file.id
            )
        }

        val sticker = stickerObj?.let { stickerObject ->
            val file = processTdFile(stickerObject.sticker, TdMessageRemoteDataSource.DownloadType.STICKER)
            WebPage.Sticker(
                path = fileHelper.resolveLocalFilePath(file),
                width = stickerObject.width,
                height = stickerObject.height,
                emoji = stickerObject.emoji,
                fileId = file.id
            )
        }

        val animation = animationObj?.let { animationObject ->
            val file = processTdFile(animationObject.animation, TdMessageRemoteDataSource.DownloadType.GIF)
            WebPage.Animation(
                path = fileHelper.resolveLocalFilePath(file),
                width = animationObject.width,
                height = animationObject.height,
                duration = animationObject.duration,
                fileId = file.id,
                thumbnailPath = animationObject.thumbnail?.file?.local?.path?.takeIf {
                    fileHelper.isValidPath(
                        it
                    )
                },
                thumbnailFileId = animationObject.thumbnail?.file?.id ?: 0,
                minithumbnail = animationObject.minithumbnail?.data
            )
        }

        return WebPage(
            url = webPage.url,
            displayUrl = webPage.displayUrl,
            type = linkPreviewType,
            siteName = webPage.siteName,
            title = webPage.title,
            description = webPage.description?.text,
            photo = photo,
            embedUrl = null,
            embedType = null,
            embedWidth = 0,
            embedHeight = 0,
            duration = duration,
            author = webPage.author,
            video = video,
            audio = audio,
            document = document,
            sticker = sticker,
            animation = animation,
            instantViewVersion = webPage.instantViewVersion
        )
    }

    internal companion object {
        internal fun selectPhotoSizes(sizes: Array<TdApi.PhotoSize>): PhotoSelection {
            val originalSize = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: sizes.lastOrNull()
            val preferredSize = sizes.find { it.type == "x" }
                ?: sizes.find { it.type == "m" }
                ?: sizes.getOrNull(sizes.size / 2)
                ?: originalSize
            val thumbnailSize = sizes.find { it.type == "m" }
                ?: sizes.find { it.type == "s" }
                ?: sizes.firstOrNull()
            return PhotoSelection(
                preferredSize = preferredSize,
                thumbnailSize = thumbnailSize,
                originalSize = originalSize
            )
        }
    }
}
