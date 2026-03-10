package org.monogram.presentation.features.stickers.core

import kotlinx.serialization.Serializable
import org.monogram.domain.models.*

@Serializable
data class StickerSetUiModel(
    val id: Long,
    val title: String,
    val name: String,
    val stickers: List<StickerUiModel>,
    val thumbnail: StickerUiModel? = null,
    val isInstalled: Boolean = false,
    val isArchived: Boolean = false,
    val isOfficial: Boolean = false,
    val stickerType: StickerTypeUi = StickerTypeUi.REGULAR
)

@Serializable
enum class StickerTypeUi {
    REGULAR,
    MASK,
    CUSTOM_EMOJI
}

@Serializable
data class StickerSetInfoUiModel(
    val id: Long,
    val title: String,
    val name: String,
    val cover: StickerUiModel? = null
)

@Serializable
enum class StickerFormatUi {
    STATIC,
    ANIMATED,
    VIDEO,
    UNKNOWN
}

@Serializable
data class StickerUiModel(
    val id: Long,
    val width: Int,
    val height: Int,
    val emoji: String,
    val path: String?,
    val format: StickerFormatUi
)

@Serializable
data class GifUiModel(
    val id: String,
    val fileId: Long,
    val thumbFileId: Long?,
    val width: Int,
    val height: Int
)

@Serializable
data class RecentEmojiUiModel(
    val emoji: String,
    val sticker: StickerUiModel? = null
)

fun StickerSetModel.toUi() = StickerSetUiModel(
    id = id,
    title = title,
    name = name,
    stickers = stickers.map { it.toUi() },
    thumbnail = thumbnail?.toUi(),
    isInstalled = isInstalled,
    isArchived = isArchived,
    isOfficial = isOfficial,
    stickerType = stickerType.toUi()
)

fun StickerSetInfoModel.toUi() = StickerSetInfoUiModel(
    id = id,
    title = title,
    name = name,
    cover = cover?.toUi()
)

fun StickerModel.toUi() = StickerUiModel(
    id = id,
    width = width,
    height = height,
    emoji = emoji,
    path = path,
    format = format.toUi()
)

fun GifModel.toUi() = GifUiModel(
    id = id,
    fileId = fileId,
    thumbFileId = thumbFileId,
    width = width,
    height = height
)

fun RecentEmojiModel.toUi() = RecentEmojiUiModel(
    emoji = emoji,
    sticker = sticker?.toUi()
)

fun StickerType.toUi() = StickerTypeUi.valueOf(name)

fun StickerFormat.toUi() = StickerFormatUi.valueOf(name)

fun StickerSetUiModel.toDomain() = StickerSetModel(
    id = id,
    title = title,
    name = name,
    stickers = stickers.map { it.toDomain() },
    thumbnail = thumbnail?.toDomain(),
    isInstalled = isInstalled,
    isArchived = isArchived,
    isOfficial = isOfficial,
    stickerType = stickerType.toDomain()
)

fun StickerSetInfoUiModel.toDomain() = StickerSetInfoModel(
    id = id,
    title = title,
    name = name,
    cover = cover?.toDomain()
)

fun StickerUiModel.toDomain() = StickerModel(
    id = id,
    width = width,
    height = height,
    emoji = emoji,
    path = path,
    format = format.toDomain()
)

fun GifUiModel.toDomain() = GifModel(
    id = id,
    fileId = fileId,
    thumbFileId = thumbFileId,
    width = width,
    height = height
)

fun RecentEmojiUiModel.toDomain() = RecentEmojiModel(
    emoji = emoji,
    sticker = sticker?.toDomain()
)

fun StickerTypeUi.toDomain() = StickerType.valueOf(name)

fun StickerFormatUi.toDomain() = StickerFormat.valueOf(name)