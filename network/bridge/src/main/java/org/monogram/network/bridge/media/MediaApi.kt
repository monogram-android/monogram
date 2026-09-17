package org.monogram.network.bridge.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.perfOp
import org.monogram.core.common.telegram.retryShortFlood
import org.monogram.core.models.PeerId
import org.monogram.core.models.Wallpaper
import org.monogram.core.models.WallpaperCatalog
import org.monogram.network.bridge.session.DispatchClass
import org.monogram.network.bridge.session.SessionCore
import org.monogram.network.http.MediaPriority
import org.monogram.network.bridge.session.isFileMigrateError
import org.monogram.network.bridge.session.isMissingThumbError
import org.monogram.network.bridge.session.nativeExceptionMessage

internal class MediaApi(private val core: SessionCore) : MediaOps {
    override suspend fun getWallpapers(hash: Long): Outcome<WallpaperCatalog> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        AppLog.api("wallpaper catalog", "start")
        return core.rpcBackground("wallpaper catalog failed") { handle ->
            core.native.getWallpapers(handle, hash).toModel()
                .also { AppLog.api("wallpaper catalog", "ok") }
        }
    }

    override suspend fun downloadWallpaper(
        wallpaper: Wallpaper,
        destPath: String
    ): Outcome<String> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        AppLog.api("wallpaper download", "start")
        return core.rpc("wallpaper download failed") { handle ->
            core.native.downloadWallpaper(handle, wallpaper.id, wallpaper.accessHash, destPath)
                .also { AppLog.api("wallpaper download", "ok") }
        }
    }

    override suspend fun downloadMessageMedia(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
    ): Outcome<String> =
        downloadMessageMedia(chatId, messageId, destPath, MediaPriority.DEFAULT)

    override suspend fun downloadMessageMedia(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
        priority: Int,
    ): Outcome<String> =
        downloadTelegramFile(chatId, messageId, destPath, TelegramFileKind.Full, priority)

    override suspend fun downloadMessageMediaChunk(
        chatId: PeerId, messageId: Int, destPath: String, offset: Long,
    ): Outcome<String> {
        require(offset >= 0 && offset % (512 * 1024) == 0L)
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        AppLog.api("stream media part", "start")
        return perfOp("stream_part") {
            try {
                val path = core.onNativeMedia { activeHandle ->
                    core.native.downloadMessageMediaChunk(
                        activeHandle,
                        chatId.value,
                        messageId,
                        destPath,
                        offset
                    )
                }
                AppLog.api("stream media part", "ok")
                Outcome.Ok(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.fail(e, "stream media part failed", degradeHome = false)
            }
        }
    }

    override suspend fun downloadMessageThumb(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
    ): Outcome<String> =
        downloadMessageThumb(chatId, messageId, destPath, MediaPriority.DEFAULT)

    override suspend fun downloadMessageThumb(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
        priority: Int,
    ): Outcome<String> =
        downloadTelegramFile(chatId, messageId, destPath, TelegramFileKind.Thumb, priority)

    override suspend fun downloadMessageDisplay(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
    ): Outcome<String> =
        downloadMessageDisplay(chatId, messageId, destPath, MediaPriority.DEFAULT)

    override suspend fun downloadMessageDisplay(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
        priority: Int,
    ): Outcome<String> =
        downloadTelegramFile(chatId, messageId, destPath, TelegramFileKind.Display, priority)

    override suspend fun downloadCustomEmoji(
        documentId: Long,
        destPath: String,
    ): Outcome<String> = downloadCustomEmoji(documentId, destPath, MediaPriority.DEFAULT)

    override suspend fun downloadCustomEmoji(
        documentId: Long,
        destPath: String,
        priority: Int,
    ): Outcome<String> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return retryShortFlood {
            try {
                val downloaded = core.onNativeClass(mediaDispatchClass(priority)) { activeHandle ->
                    core.native.downloadCustomEmoji(activeHandle, documentId, destPath)
                }
                Outcome.Ok(downloaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Err(e.message ?: "custom emoji download failed")
            }
        }
    }

    private enum class TelegramFileKind { Full, Thumb, Display }

    private fun mediaDispatchClass(priority: Int): Int =
        if (MediaPriority.isBackground(priority)) DispatchClass.BACKGROUND_MEDIA
        else DispatchClass.INTERACTIVE_MEDIA

    private suspend fun downloadTelegramFile(
        chatId: PeerId,
        messageId: Int,
        destPath: String,
        kind: TelegramFileKind,
        priority: Int,
    ): Outcome<String> {
        AppLog.api(
            "download media",
            "wait ${kind.name} chat=${chatId.value} id=$messageId pri=$priority",
        )
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> {
                AppLog.api(
                    "download media",
                    "ensureConnected err ${connected.message} ${kind.name} chat=${chatId.value} id=$messageId",
                )
                return connected
            }
            is Outcome.Ok -> Unit
        }
        val dispatchClass = mediaDispatchClass(priority)
        return retryShortFlood {
            perfOp("download:${kind.name.lowercase()}") {
                try {
                    AppLog.api(
                        "download media",
                        "start ${kind.name} chat=${chatId.value} id=$messageId pri=$priority",
                    )
                    suspend fun fetch(): String = core.onNativeClass(dispatchClass) { activeHandle ->
                        when (kind) {
                            TelegramFileKind.Thumb ->
                                core.native.downloadMessageThumb(
                                    activeHandle,
                                    chatId.value,
                                    messageId,
                                    destPath
                                )

                            TelegramFileKind.Display ->
                                core.native.downloadMessageDisplay(
                                    activeHandle,
                                    chatId.value,
                                    messageId,
                                    destPath
                                )

                            TelegramFileKind.Full ->
                                core.native.downloadMessageMedia(
                                    activeHandle,
                                    chatId.value,
                                    messageId,
                                    destPath
                                )
                        }
                    }
                    val downloaded = try {
                        fetch()
                    } catch (e: Exception) {
                        val raw = nativeExceptionMessage(e)
                        if (messageId > 0 && raw.contains("no media for chat")) {
                            delay(400)
                            fetch()
                        } else {
                            throw e
                        }
                    }
                    AppLog.api("download media", "ok")
                    Outcome.Ok(downloaded)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val raw = nativeExceptionMessage(e)
                    if (kind == TelegramFileKind.Thumb && isMissingThumbError(raw)) {
                        return@retryShortFlood Outcome.Err("no downloadable thumb")
                    }
                    if (kind == TelegramFileKind.Display && raw.contains(
                            "no display size",
                            ignoreCase = true
                        )
                    ) {
                        return@retryShortFlood Outcome.Err("no display size")
                    }
                    if (isFileMigrateError(raw)) {
                        return@retryShortFlood Outcome.Err("media deferred")
                    }
                    val outcome = core.fail(e, "download media failed", degradeHome = false)
                    val homeAlive = core.activeHandleOrZero().let { activeHandle ->
                        activeHandle == 0L || runCatching { core.native.isAuthorized(activeHandle) }.getOrDefault(
                            true
                        )
                    }
                    if (!homeAlive) {
                        core.markSessionDegraded(outcome.telegramError)
                    }
                    outcome
                }
            }
        }
    }
}
