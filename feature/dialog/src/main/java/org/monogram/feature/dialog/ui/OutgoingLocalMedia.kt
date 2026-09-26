package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.core.models.Message
import org.monogram.core.ui.components.MediaPlaceholder
import org.monogram.feature.dialog.R
import java.io.File

@Composable
internal fun OutgoingLocalMedia(
    message: Message,
    file: File,
    modifier: Modifier,
    fillBounds: Boolean,
    edgeToEdge: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    val kind = message.mediaKind ?: return
    val pending = message.pending || message.id.id < 0
    val failed = message.failed && !pending
    val photoSize = if (edgeToEdge) {
        bubbleEdgeMediaDisplaySize(kind, message.mediaWidth, message.mediaHeight)
    } else {
        visualMediaDisplaySize(kind, message.mediaWidth, message.mediaHeight)
    }
    val durationAtStart = edgeToEdge &&
        !shouldShowMessageCaption(message.mediaKind, message.text, message.fileName)
    val mediaModifier = if (fillBounds) {
        modifier
    } else {
        modifier
            .size(photoSize.first.dp, photoSize.second.dp)
            .then(
                if (edgeToEdge) {
                    Modifier
                } else {
                    Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                },
            )
    }
    when (kind) {
        "document" -> DocumentBubble(
            onLongPress = onLongPress,
            file = null,
            thumb = file.takeIf { stillImageFile(it) },
            name = message.fileName ?: file.name,
            size = message.fileSize ?: file.length(),
            loading = pending,
            failed = failed,
            uploading = pending,
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        "video", "gif" -> {
            val videoFrame by produceState<File?>(initialValue = null, file) {
                value = if (stillImageFile(file)) {
                    file
                } else {
                    withContext(Dispatchers.IO) { extractVideoFrame(file) }
                }
            }
            val preview = file.takeIf { stillImageFile(it) } ?: videoFrame
            Box(modifier = mediaModifier, contentAlignment = Alignment.Center) {
                VideoThumb(
                    thumb = preview,
                    image = preview,
                    durationSeconds = message.mediaDuration,
                    failed = failed,
                    loading = false,
                    fileSize = message.fileSize ?: file.length(),
                    onPlay = {},
                    compact = fillBounds,
                    durationAtStart = durationAtStart,
                    onLongPress = onLongPress,
                    modifier = Modifier.fillMaxSize(),
                )
                MediaProgressOverlay(visible = pending, uploading = true)
            }
        }
        else -> {
            Box(modifier = mediaModifier, contentAlignment = Alignment.Center) {
                if (stillImageFile(file)) {
                    AsyncImage(
                        model = file,
                        contentDescription = stringResource(R.string.dialog_media_photo),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
                MediaProgressOverlay(visible = pending, uploading = true)
                if (!pending && failed) {
                    MediaPlaceholder(
                        failed = true,
                        failedText = stringResource(R.string.dialog_media_failed),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun extractVideoFrame(file: File): File? {
    if (!file.isFile || file.length() <= 0L) return null
    val dest = File(file.parentFile, "${file.name}.thumb.jpg")
    if (dest.isFile && dest.length() > 0L) return dest
    val retriever = android.media.MediaMetadataRetriever()
    return runCatching {
        retriever.setDataSource(file.absolutePath)
        val bitmap = retriever.getFrameAtTime(
            0L,
            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        ) ?: return@runCatching null
        dest.outputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, output)
        }
        dest.takeIf { it.isFile && it.length() > 0L }
    }.getOrNull().also {
        runCatching { retriever.release() }
    }
}
