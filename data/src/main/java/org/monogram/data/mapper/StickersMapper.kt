package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.StickerFormat
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.models.StickerType

fun TdApi.Sticker.toDomain(): StickerModel = StickerModel(
    id = sticker.id.toLong(),
    customEmojiId = (fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId,
    width = width,
    height = height,
    emoji = emoji,
    path = sticker.local.path.takeIf { isValidFilePath(it) },
    format = format.toDomain()
)

fun TdApi.StickerSet.toDomain(): StickerSetModel = StickerSetModel(
    id = id,
    title = title,
    name = name,
    stickers = stickers.map { it.toDomain() },
    thumbnail = thumbnail?.let { thumb ->
        StickerModel(
            id = thumb.file.id.toLong(),
            width = thumb.width,
            height = thumb.height,
            emoji = "",
            path = thumb.file.local.path.takeIf { isValidFilePath(it) },
            format = stickers.firstOrNull()?.format.toDomain()
        )
    },
    isInstalled = isInstalled,
    isArchived = isArchived,
    isOfficial = isOfficial,
    stickerType = when (stickerType) {
        is TdApi.StickerTypeRegular -> StickerType.REGULAR
        is TdApi.StickerTypeMask -> StickerType.MASK
        is TdApi.StickerTypeCustomEmoji -> StickerType.CUSTOM_EMOJI
        else -> StickerType.REGULAR
    }
)

fun TdApi.StickerFormat?.toDomain(): StickerFormat = when (this) {
    is TdApi.StickerFormatWebp -> StickerFormat.STATIC
    is TdApi.StickerFormatTgs -> StickerFormat.ANIMATED
    is TdApi.StickerFormatWebm -> StickerFormat.VIDEO
    else -> StickerFormat.UNKNOWN
}

fun StickerType.toApi(): TdApi.StickerType = when (this) {
    StickerType.REGULAR -> TdApi.StickerTypeRegular()
    StickerType.MASK -> TdApi.StickerTypeMask()
    StickerType.CUSTOM_EMOJI -> TdApi.StickerTypeCustomEmoji()
}