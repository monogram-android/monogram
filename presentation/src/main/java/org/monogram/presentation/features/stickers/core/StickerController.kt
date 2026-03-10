package org.monogram.presentation.features.stickers.core

import androidx.compose.ui.graphics.ImageBitmap

interface StickerController {
    val currentImageBitmap: ImageBitmap?
    val frameVersion: Long

    fun start()
    fun setPaused(paused: Boolean)
    fun release()

    suspend fun renderFirstFrame(): ImageBitmap? = null
}