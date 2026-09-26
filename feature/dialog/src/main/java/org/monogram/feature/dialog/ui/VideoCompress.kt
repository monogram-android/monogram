package org.monogram.feature.dialog.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult

internal fun readVideoInfo(source: File): VideoInfo? {
    val retriever = MediaMetadataRetriever()
    val info = runCatching {
        retriever.setDataSource(source.absolutePath)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            ?.toIntOrNull() ?: 0
        if (width <= 0 || height <= 0) null else VideoInfo(width, height, bitrate, source.length())
    }.getOrNull()
    runCatching { retriever.release() }
    return info
}

internal suspend fun compressVideoLikeAndroid(
    context: Context,
    source: File,
    destination: File,
): File? {
    val info = withContext(Dispatchers.IO) { readVideoInfo(source) } ?: return null
    val target = videoTarget(info) ?: return null
    return withContext(Dispatchers.Main.immediate) {
        runCatching {
            val encoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(target.bitrate)
                .setiFrameIntervalSeconds(1f)
                .build()
            val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
                .setRequestedVideoEncoderSettings(encoderSettings)
                .build()
            val effects = Effects(
                emptyList(),
                listOf(
                    Presentation.createForWidthAndHeight(
                        target.width,
                        target.height,
                        Presentation.LAYOUT_SCALE_TO_FIT,
                    ),
                ),
            )
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setEffects(effects)
                .build()
            val transformer = Transformer.Builder(context.applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .build()
            val failure = suspendCancellableCoroutine<Exception?> { continuation ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        if (continuation.isActive) continuation.resume(exception)
                    }
                })
                continuation.invokeOnCancellation { runCatching { transformer.cancel() } }
                transformer.start(item, destination.absolutePath)
            }
            if (failure != null || !destination.isFile || destination.length() <= 0L) {
                destination.delete()
                null
            } else {
                destination
            }
        }.getOrElse {
            destination.delete()
            null
        }
    }
}
