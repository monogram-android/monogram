package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.TdMessageRemoteDataSource
import org.monogram.domain.models.WebPage
import org.monogram.domain.repository.AppPreferencesProvider

internal class WebPageMapper(
    private val fileHelper: TdFileHelper,
    private val appPreferences: AppPreferencesProvider
) {
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
            val size = photoObject.sizes.firstOrNull()
            if (size != null) {
                val file = processTdFile(size.photo, TdMessageRemoteDataSource.DownloadType.DEFAULT)
                val bestPath = fileHelper.findBestAvailablePath(file, photoObject.sizes)

                WebPage.Photo(
                    path = bestPath,
                    width = size.width,
                    height = size.height,
                    fileId = file.id,
                    minithumbnail = photoObject.minithumbnail?.data
                )
            } else {
                null
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
}