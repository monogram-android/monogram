package org.monogram.core.models

data class Wallpaper(
    val id: Long,
    val accessHash: Long,
    val slug: String,
    val pattern: Boolean,
    val dark: Boolean,
    val mimeType: String,
    val documentId: Long?,
    val colors: List<Int>,
    val intensity: Int?,
    val rotation: Int,
    val blur: Boolean,
    val motion: Boolean,
)

data class WallpaperCatalog(
    val hash: Long,
    val notModified: Boolean,
    val wallpapers: List<Wallpaper>,
)
