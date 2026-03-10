package org.monogram.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class StickerSetModel(
    val id: Long,
    val title: String,
    val name: String,
    val stickers: List<StickerModel>,
    val thumbnail: StickerModel? = null,
    val isInstalled: Boolean = false,
    val isArchived: Boolean = false,
    val isOfficial: Boolean = false,
    val stickerType: StickerType = StickerType.REGULAR
)

@Serializable
enum class StickerType {
    REGULAR,
    MASK,
    CUSTOM_EMOJI
}

data class StickerSetInfoModel(
    val id: Long,
    val title: String,
    val name: String,
    val cover: StickerModel? = null
)

@Serializable
enum class StickerFormat {
    STATIC,
    ANIMATED,
    VIDEO,
    UNKNOWN
}

@Serializable
data class StickerModel(
    val id: Long,
    val width: Int,
    val height: Int,
    val emoji: String,
    val path: String?,
    val format: StickerFormat
)

@Serializable
data class GifModel(
    val id: String,
    val fileId: Long,
    val thumbFileId: Long?,
    val width: Int,
    val height: Int
)

@Serializable
data class RecentEmojiModel(
    val emoji: String,
    val sticker: StickerModel? = null
)
