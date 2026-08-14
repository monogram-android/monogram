package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.MessageFileApi
import org.monogram.data.datasource.remote.TdMessageRemoteDataSource.DownloadType
import org.monogram.data.infra.FileDownloadQueue.DemandRole
import org.monogram.data.infra.FileDownloadQueue.MediaDescriptor
import org.monogram.data.infra.FileDownloadQueue.MediaKind

internal class MappedMediaDemandCoordinator(
    private val fileApi: MessageFileApi
) {
    fun register(message: TdApi.Message) {
        descriptors(message.content).distinct().forEach { descriptor ->
            fileApi.registerFileForMessage(
                fileId = descriptor.fileId,
                chatId = message.chatId,
                messageId = message.id,
                type = descriptor.type,
                descriptor = descriptor.metadata
            )
        }
    }

    private fun descriptors(content: TdApi.MessageContent): Sequence<FileDescriptor> = sequence {
        when (content) {
            is TdApi.MessagePhoto -> yieldPhoto(content.photo)
            is TdApi.MessageVideo -> {
                yieldFile(
                    content.video.video,
                    DownloadType.VIDEO,
                    MediaKind.VIDEO,
                    if (content.video.supportsStreaming) DemandRole.MANUAL_ONLY else DemandRole.PRIMARY,
                    content.video.supportsStreaming
                )
                yieldFile(
                    content.video.thumbnail?.file,
                    kind = MediaKind.PHOTO,
                    role = DemandRole.PREVIEW
                )
            }

            is TdApi.MessageVoiceNote -> yieldFile(content.voiceNote.voice, kind = MediaKind.VOICE)
            is TdApi.MessageVideoNote -> {
                yieldFile(content.videoNote.video, DownloadType.VIDEO_NOTE, MediaKind.VIDEO_NOTE)
                yieldFile(
                    content.videoNote.thumbnail?.file,
                    kind = MediaKind.PHOTO,
                    role = DemandRole.PREVIEW
                )
            }

            is TdApi.MessageSticker -> yieldFile(
                content.sticker.sticker,
                DownloadType.STICKER,
                MediaKind.STICKER
            )

            is TdApi.MessageAnimation -> {
                yieldFile(content.animation.animation, DownloadType.GIF, MediaKind.GIF)
                yieldFile(
                    content.animation.thumbnail?.file,
                    kind = MediaKind.PHOTO,
                    role = DemandRole.PREVIEW
                )
            }

            is TdApi.MessageDocument -> {
                yieldFile(content.document.document, kind = MediaKind.DOCUMENT)
                yieldFile(
                    content.document.thumbnail?.file,
                    kind = MediaKind.PHOTO,
                    role = DemandRole.PREVIEW
                )
            }

            is TdApi.MessageAudio -> yieldFile(content.audio.audio, kind = MediaKind.AUDIO)
            is TdApi.MessageText -> content.linkPreview?.let { yieldLinkPreview(it) }
            is TdApi.MessagePaidMedia -> content.media.forEach { media ->
                when (media) {
                    is TdApi.PaidMediaPhoto -> yieldPhoto(media.photo)
                    is TdApi.PaidMediaVideo -> {
                        yieldFile(media.video.video, DownloadType.VIDEO, MediaKind.VIDEO)
                        yieldFile(
                            media.video.thumbnail?.file,
                            kind = MediaKind.PHOTO,
                            role = DemandRole.PREVIEW
                        )
                        media.cover?.let { yieldPhoto(it) }
                    }
                }
            }
        }
    }

    private suspend fun SequenceScope<FileDescriptor>.yieldLinkPreview(preview: TdApi.LinkPreview) {
        when (val type = preview.type) {
            is TdApi.LinkPreviewTypePhoto -> yieldPhoto(type.photo)
            is TdApi.LinkPreviewTypeArticle -> type.photo?.let { yieldPhoto(it) }
            is TdApi.LinkPreviewTypeVideo -> {
                yieldFile(
                    type.video.video,
                    DownloadType.VIDEO,
                    MediaKind.VIDEO,
                    DemandRole.MANUAL_ONLY,
                    type.video.supportsStreaming
                )
                yieldFile(
                    type.video.thumbnail?.file,
                    kind = MediaKind.PHOTO,
                    role = DemandRole.PREVIEW
                )
            }

            is TdApi.LinkPreviewTypeAnimation -> {
                yieldFile(type.animation.animation, DownloadType.GIF, MediaKind.GIF)
                yieldFile(
                    type.animation.thumbnail?.file,
                    kind = MediaKind.PHOTO,
                    role = DemandRole.PREVIEW
                )
            }

            is TdApi.LinkPreviewTypeAudio -> yieldFile(
                type.audio.audio,
                kind = MediaKind.AUDIO,
                role = DemandRole.MANUAL_ONLY
            )

            is TdApi.LinkPreviewTypeDocument -> yieldFile(
                type.document.document,
                kind = MediaKind.DOCUMENT,
                role = DemandRole.MANUAL_ONLY
            )

            is TdApi.LinkPreviewTypeSticker -> yieldFile(
                type.sticker.sticker,
                DownloadType.STICKER,
                MediaKind.STICKER
            )
        }
    }

    private suspend fun SequenceScope<FileDescriptor>.yieldPhoto(photo: TdApi.Photo) {
        val sizes = photo.sizes
        val original =
            sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: sizes.lastOrNull()
        val primary = sizes.find { it.type == "x" }
            ?: sizes.find { it.type == "m" }
            ?: sizes.getOrNull(sizes.size / 2)
            ?: original
        val preview = sizes.find { it.type == "m" }
            ?: sizes.find { it.type == "s" }
            ?: sizes.firstOrNull()
        preview?.photo?.let { yieldFile(it, kind = MediaKind.PHOTO, role = DemandRole.PREVIEW) }
        primary?.photo?.let { yieldFile(it, kind = MediaKind.PHOTO, role = DemandRole.PRIMARY) }
        original?.photo?.takeIf { it.id != primary?.photo?.id }
            ?.let { yieldFile(it, kind = MediaKind.PHOTO, role = DemandRole.MANUAL_ONLY) }
    }

    private suspend fun SequenceScope<FileDescriptor>.yieldFile(
        file: TdApi.File?,
        type: DownloadType = DownloadType.DEFAULT,
        kind: MediaKind = MediaKind.OTHER,
        role: DemandRole = DemandRole.PRIMARY,
        supportsStreaming: Boolean = false
    ) {
        if (file != null && file.id != 0) yield(
            FileDescriptor(
                fileId = file.id,
                type = type,
                metadata = MediaDescriptor(
                    kind = kind,
                    role = role,
                    size = maxOf(file.size, file.expectedSize),
                    supportsStreaming = supportsStreaming
                )
            )
        )
    }

    private data class FileDescriptor(
        val fileId: Int,
        val type: DownloadType,
        val metadata: MediaDescriptor
    )
}
