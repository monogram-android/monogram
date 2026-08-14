package org.monogram.presentation.features.chats.conversation.logic

import org.monogram.domain.models.MessageContent

internal fun MessageContent.withFileDownloadState(
    fileId: Int,
    isDownloading: Boolean,
    progress: Float
): MessageContent {
    if (fileId == 0) return this
    val normalizedProgress = progress.coerceIn(0f, 1f)

    return when (this) {
        is MessageContent.Photo -> if (this.fileId == fileId) copy(
            isDownloading = isDownloading,
            downloadProgress = normalizedProgress,
            downloadError = false
        ) else this

        is MessageContent.Video -> if (this.fileId == fileId) copy(
            isDownloading = isDownloading,
            downloadProgress = normalizedProgress,
            downloadError = false
        ) else this

        is MessageContent.VideoNote -> if (this.fileId == fileId) copy(
            isDownloading = isDownloading,
            downloadProgress = normalizedProgress,
            downloadError = false
        ) else this

        is MessageContent.Document -> if (this.fileId == fileId) copy(
            isDownloading = isDownloading,
            downloadProgress = normalizedProgress,
            downloadError = false
        ) else this

        is MessageContent.Audio -> if (this.fileId == fileId) copy(
            isDownloading = isDownloading,
            downloadProgress = normalizedProgress,
            downloadError = false
        ) else this

        is MessageContent.Gif -> if (this.fileId == fileId) copy(
            isDownloading = isDownloading,
            downloadProgress = normalizedProgress,
            downloadError = false
        ) else this

        is MessageContent.Voice -> if (this.fileId == fileId) copy(
            isDownloading = isDownloading,
            downloadProgress = normalizedProgress,
            downloadError = false
        ) else this

        is MessageContent.Sticker -> if (this.fileId == fileId) copy(
            isDownloading = isDownloading,
            downloadProgress = normalizedProgress,
            downloadError = false
        ) else this

        else -> this
    }
}
