package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.ThumbnailModel
import org.monogram.domain.models.WallpaperModel
import org.monogram.domain.models.WallpaperSettings
import org.monogram.domain.models.WallpaperType

fun mapBackgrounds(backgrounds: Array<TdApi.Background>): List<WallpaperModel> {
    val defaultWallpapers = listOf(
        WallpaperModel(
            id = -1,
            slug = "default_blue",
            title = "Default Blue",
            type = WallpaperType.FILL,
            pattern = false,
            documentId = 0,
            thumbnail = null,
            settings = WallpaperSettings(
                backgroundColor = 0x1E3557,
                secondBackgroundColor = 0x2D4A77,
                thirdBackgroundColor = null,
                fourthBackgroundColor = null,
                intensity = null,
                rotation = 45,
                isInverted = null,
                isMoving = null,
                isBlurred = null
            ),
            themeName = null,
            isDownloaded = true,
            localPath = null,
            isDefault = true
        )
    )
    return defaultWallpapers + backgrounds.map { it.toDomain() }
}

fun TdApi.Background.toDomain(): WallpaperModel {
    val doc = this.document
    val file = doc?.document
    return WallpaperModel(
        id = this.id,
        slug = this.name,
        title = this.name,
        type = this.type.toWallpaperType(),
        pattern = this.type is TdApi.BackgroundTypePattern,
        documentId = doc?.document?.id?.toLong() ?: 0L,
        thumbnail = doc?.thumbnail?.toDomain(),
        settings = this.type.toWallpaperSettings(),
        themeName = this.type.toThemeName(),
        isDownloaded = file?.local?.isDownloadingCompleted == true,
        localPath = file?.local?.path?.ifEmpty { null },
        isDefault = this.isDefault
    )
}

fun TdApi.Thumbnail.toDomain(): ThumbnailModel = ThumbnailModel(
    fileId = this.file.id,
    width = this.width,
    height = this.height,
    localPath = this.file.local.path
)

fun TdApi.BackgroundType.toWallpaperType(): WallpaperType = when (this) {
    is TdApi.BackgroundTypeWallpaper -> WallpaperType.WALLPAPER
    is TdApi.BackgroundTypePattern -> WallpaperType.PATTERN
    is TdApi.BackgroundTypeFill -> WallpaperType.FILL
    is TdApi.BackgroundTypeChatTheme -> WallpaperType.CHAT_THEME
    else -> WallpaperType.WALLPAPER
}

fun TdApi.BackgroundType.toThemeName(): String? = when (this) {
    is TdApi.BackgroundTypeChatTheme -> themeName
    else -> null
}

fun TdApi.BackgroundType.toWallpaperSettings(): WallpaperSettings? = when (this) {
    is TdApi.BackgroundTypePattern -> fill.toWallpaperSettings()
        ?.copy(intensity = intensity, isInverted = isInverted, isMoving = isMoving)
    is TdApi.BackgroundTypeFill -> fill.toWallpaperSettings()
    is TdApi.BackgroundTypeWallpaper -> WallpaperSettings(
        backgroundColor = null,
        secondBackgroundColor = null,
        thirdBackgroundColor = null,
        fourthBackgroundColor = null,
        intensity = null,
        rotation = null,
        isInverted = null,
        isMoving = isMoving,
        isBlurred = isBlurred
    )

    is TdApi.BackgroundTypeChatTheme -> null
    else -> null
}

fun TdApi.BackgroundFill.toWallpaperSettings(): WallpaperSettings? = when (this) {
    is TdApi.BackgroundFillSolid -> WallpaperSettings(
        backgroundColor = color,
        secondBackgroundColor = null,
        thirdBackgroundColor = null,
        fourthBackgroundColor = null,
        intensity = null,
        rotation = null,
        isInverted = null,
        isMoving = null,
        isBlurred = null
    )
    is TdApi.BackgroundFillGradient -> WallpaperSettings(
        backgroundColor = topColor,
        secondBackgroundColor = bottomColor,
        thirdBackgroundColor = null,
        fourthBackgroundColor = null,
        intensity = null,
        rotation = rotationAngle,
        isInverted = null,
        isMoving = null,
        isBlurred = null
    )
    is TdApi.BackgroundFillFreeformGradient -> WallpaperSettings(
        backgroundColor = colors.getOrNull(0),
        secondBackgroundColor = colors.getOrNull(1),
        thirdBackgroundColor = colors.getOrNull(2),
        fourthBackgroundColor = colors.getOrNull(3),
        intensity = null,
        rotation = null,
        isInverted = null,
        isMoving = null,
        isBlurred = null
    )
    else -> null
}

fun WallpaperModel.toInputBackground(): TdApi.InputBackground? = when (resolveWallpaperType()) {
    WallpaperType.WALLPAPER -> when {
        id > 0L -> TdApi.InputBackgroundRemote(id)
        !localPath.isNullOrBlank() -> TdApi.InputBackgroundLocal(TdApi.InputFileLocal(localPath))
        else -> null
    }

    WallpaperType.PATTERN,
    WallpaperType.FILL,
    WallpaperType.CHAT_THEME -> if (id > 0L) TdApi.InputBackgroundRemote(id) else null
}

fun WallpaperModel.toBackgroundType(isBlurred: Boolean, isMoving: Boolean): TdApi.BackgroundType? =
    when (resolveWallpaperType()) {
        WallpaperType.WALLPAPER -> TdApi.BackgroundTypeWallpaper(isBlurred, isMoving)

        WallpaperType.PATTERN -> {
            val wallpaperSettings = settings ?: return null
            val fill = wallpaperSettings.toBackgroundFill() ?: return null
            TdApi.BackgroundTypePattern(
                fill,
                wallpaperSettings.intensity ?: 50,
                wallpaperSettings.isInverted == true,
                isMoving
            )
        }

        WallpaperType.FILL -> {
            val wallpaperSettings = settings ?: return null
            val fill = wallpaperSettings.toBackgroundFill() ?: return null
            TdApi.BackgroundTypeFill(fill)
        }

        WallpaperType.CHAT_THEME -> {
            val name = themeName?.takeIf { it.isNotBlank() } ?: slug.takeIf { it.isNotBlank() }
            name?.let { TdApi.BackgroundTypeChatTheme(it) }
        }
    }

private fun WallpaperSettings.toBackgroundFill(): TdApi.BackgroundFill? {
    val first = backgroundColor?.toTdColor()
    val second = secondBackgroundColor?.toTdColor()
    val third = thirdBackgroundColor?.toTdColor()
    val fourth = fourthBackgroundColor?.toTdColor()

    val freeform = intArrayOfNotNull(first, second, third, fourth)
    if (freeform.size >= 3) {
        return TdApi.BackgroundFillFreeformGradient(freeform)
    }

    if (first != null && second != null) {
        return TdApi.BackgroundFillGradient(first, second, rotation ?: 0)
    }

    if (first != null) {
        return TdApi.BackgroundFillSolid(first)
    }

    return null
}

private fun WallpaperModel.resolveWallpaperType(): WallpaperType = when {
    type == WallpaperType.PATTERN || pattern -> WallpaperType.PATTERN
    type == WallpaperType.CHAT_THEME || slug.startsWith("emoji") -> WallpaperType.CHAT_THEME
    type == WallpaperType.FILL -> WallpaperType.FILL
    documentId != 0L || slug == "built-in" -> WallpaperType.WALLPAPER
    else -> WallpaperType.FILL
}

private fun intArrayOfNotNull(vararg values: Int?): IntArray =
    values.filterNotNull().toIntArray()

private fun Int.toTdColor(): Int = this and 0x00FFFFFF
