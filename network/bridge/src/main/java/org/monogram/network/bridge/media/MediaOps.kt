package org.monogram.network.bridge.media

import org.monogram.core.common.Outcome
import org.monogram.core.models.PeerId
import org.monogram.core.models.Wallpaper
import org.monogram.core.models.WallpaperCatalog

interface MediaOps {
    suspend fun getWallpapers(hash: Long = 0): Outcome<WallpaperCatalog> =
        Outcome.Err("Wallpaper catalog unavailable")

    suspend fun downloadWallpaper(wallpaper: Wallpaper, destPath: String): Outcome<String> =
        Outcome.Err("Wallpaper download unavailable")

    suspend fun downloadMessageMedia(
        chatId: PeerId,
        messageId: Int,
        destPath: String
    ): Outcome<String>

    suspend fun downloadMessageMedia(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
        priority: Int,
    ): Outcome<String> = downloadMessageMedia(chatId, messageId, destPath)

    suspend fun downloadMessageMediaChunk(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
        offset: Long
    ): Outcome<String> =
        Outcome.Err("Media streaming unavailable")

    suspend fun downloadMessageThumb(
        chatId: PeerId,
        messageId: Int,
        destPath: String
    ): Outcome<String>

    suspend fun downloadMessageThumb(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
        priority: Int,
    ): Outcome<String> = downloadMessageThumb(chatId, messageId, destPath)

    suspend fun downloadMessageDisplay(
        chatId: PeerId,
        messageId: Int,
        destPath: String
    ): Outcome<String> =
        downloadMessageMedia(chatId, messageId, destPath)

    suspend fun downloadMessageDisplay(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
        priority: Int,
    ): Outcome<String> = downloadMessageDisplay(chatId, messageId, destPath)

    suspend fun downloadCustomEmoji(documentId: Long, destPath: String): Outcome<String> =
        Outcome.Err("unsupported")

    suspend fun downloadCustomEmoji(
        documentId: Long,
        destPath: String,
        priority: Int,
    ): Outcome<String> = downloadCustomEmoji(documentId, destPath)

    fun peekMessageInlineThumb(chatId: PeerId, messageId: Int): ByteArray? = null
}
