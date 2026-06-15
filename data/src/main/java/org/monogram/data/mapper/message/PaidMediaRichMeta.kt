package org.monogram.data.mapper.message

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.PaidMediaItem

private val paidMediaRichMetaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun PaidMediaPayload.encode(): String =
    paidMediaRichMetaJson.encodeToString(PaidMediaPayload.serializer(), this)

internal fun decodePaidMediaPayload(raw: String?): PaidMediaPayload? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        paidMediaRichMetaJson.decodeFromString(PaidMediaPayload.serializer(), raw)
    }.getOrNull()
}

@Serializable
internal data class PaidMediaPayload(
    val starCount: Long,
    val caption: String,
    val entities: List<MessageEntity> = emptyList(),
    val showCaptionAboveMedia: Boolean = false,
    val items: List<PaidMediaItemPayload> = emptyList()
)

@Serializable
internal sealed interface PaidMediaItemPayload {
    @Serializable
    data class Preview(
        val width: Int,
        val height: Int,
        val duration: Int,
        val minithumbnail: ByteArray? = null
    ) : PaidMediaItemPayload

    @Serializable
    data class Photo(
        val path: String? = null,
        val thumbnailPath: String? = null,
        val width: Int,
        val height: Int,
        val fileId: Int,
        val originalFileId: Int,
        val minithumbnail: ByteArray? = null,
        val livePhotoVideoPath: String? = null,
        val livePhotoVideoFileId: Int = 0
    ) : PaidMediaItemPayload

    @Serializable
    data class Video(
        val path: String? = null,
        val thumbnailPath: String? = null,
        val width: Int,
        val height: Int,
        val duration: Int,
        val fileId: Int,
        val minithumbnail: ByteArray? = null,
        val supportsStreaming: Boolean = false,
        val coverPath: String? = null,
        val startTimestamp: Int = 0
    ) : PaidMediaItemPayload

    @Serializable
    data object Unsupported : PaidMediaItemPayload
}

internal fun PaidMediaItem.toPayload(): PaidMediaItemPayload = when (this) {
    is PaidMediaItem.Preview -> PaidMediaItemPayload.Preview(width, height, duration, minithumbnail)
    is PaidMediaItem.Photo -> PaidMediaItemPayload.Photo(
        path = path,
        thumbnailPath = thumbnailPath,
        width = width,
        height = height,
        fileId = fileId,
        originalFileId = originalFileId,
        minithumbnail = minithumbnail,
        livePhotoVideoPath = livePhotoVideoPath,
        livePhotoVideoFileId = livePhotoVideoFileId
    )

    is PaidMediaItem.Video -> PaidMediaItemPayload.Video(
        path = path,
        thumbnailPath = thumbnailPath,
        width = width,
        height = height,
        duration = duration,
        fileId = fileId,
        minithumbnail = minithumbnail,
        supportsStreaming = supportsStreaming,
        coverPath = coverPath,
        startTimestamp = startTimestamp
    )

    PaidMediaItem.Unsupported -> PaidMediaItemPayload.Unsupported
}

internal fun PaidMediaItemPayload.toDomain(): PaidMediaItem = when (this) {
    is PaidMediaItemPayload.Preview -> PaidMediaItem.Preview(width, height, duration, minithumbnail)
    is PaidMediaItemPayload.Photo -> PaidMediaItem.Photo(
        path = path,
        thumbnailPath = thumbnailPath,
        width = width,
        height = height,
        fileId = fileId,
        originalFileId = originalFileId,
        minithumbnail = minithumbnail,
        livePhotoVideoPath = livePhotoVideoPath,
        livePhotoVideoFileId = livePhotoVideoFileId
    )

    is PaidMediaItemPayload.Video -> PaidMediaItem.Video(
        path = path,
        thumbnailPath = thumbnailPath,
        width = width,
        height = height,
        duration = duration,
        fileId = fileId,
        minithumbnail = minithumbnail,
        supportsStreaming = supportsStreaming,
        coverPath = coverPath,
        startTimestamp = startTimestamp
    )

    PaidMediaItemPayload.Unsupported -> PaidMediaItem.Unsupported
}
