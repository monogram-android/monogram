package org.monogram.network.bridge.media

import org.monogram.core.models.Wallpaper
import org.monogram.core.models.WallpaperCatalog
import uniffi.monogram_mtproto.WallpaperCatalogDto

internal fun WallpaperCatalogDto.toModel() = WallpaperCatalog(
    hash = hash,
    notModified = notModified,
    wallpapers = wallpapers.map {
        Wallpaper(
            it.id, it.accessHash, it.slug, it.pattern, it.dark, it.mimeType, it.documentId,
            it.colors, it.intensity, it.rotation, it.blur, it.motion
        )
    },
)
