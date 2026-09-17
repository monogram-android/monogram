package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.core.models.InstantViewBlock
import org.monogram.core.models.InstantViewPages
import org.monogram.core.ui.loading.MonogramCircularProgress
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository
import java.io.File

@Composable
internal fun InstantViewDocument(
    block: InstantViewBlock.Document,
    scale: Float,
    query: String,
    mediaRepository: MediaRepository?,
    onOpenUrl: (String) -> Unit,
) {
    val media = rememberIvMedia(block.cacheKey, mediaRepository, autoFetchFull = block.loop)
    val file = media.image ?: media.thumb
    val aspect = documentAspectRatio(block.width, block.height)
    when (block.kind) {
        "video" -> {
            val full = media.image
            if (full != null && !stillImageFile(full)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    VideoPlayer(
                        file = full,
                        isGif = block.loop,
                        durationSeconds = block.durationSeconds,
                        modifier = Modifier.fillMaxSize(),
                        compact = true,
                    )
                }
            } else {
                InstantViewVideoPoster(
                    thumb = media.thumb?.takeIf { stillImageFile(it) },
                    loading = media.loading || media.fullLoading,
                    failed = media.failed,
                    onPlay = media.requestFull,
                    aspectRatio = aspect,
                )
            }
        }
        "audio" -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = block.title ?: block.fileName ?: stringResource(R.string.dialog_media_audio),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = (16 * scale).sp),
            )
            val subtitle = listOfNotNull(
                block.performer?.takeIf { it.isNotBlank() },
                block.durationSeconds?.takeIf { it > 0 }?.let(::formatMediaDuration),
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            file?.let {
                VideoPlayer(
                    file = it,
                    isGif = false,
                    durationSeconds = block.durationSeconds,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    compact = true,
                )
            }
        }
        else -> {
            val context = LocalContext.current
            val name = block.fileName ?: stringResource(R.string.dialog_media_document)
            val size = InstantViewPages.formatFileSize(block.fileSize)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = file != null) {
                        val local = file ?: return@clickable
                        val mime = block.mimeType?.takeIf { it.isNotBlank() } ?: mimeFromName(name)
                        runCatching {
                            context.startActivity(viewFileIntent(context, fileForExternalView(local, name), mime))
                        }
                    }
                    .padding(12.dp),
            ) {
                Text(name, style = MaterialTheme.typography.titleSmall.copy(fontSize = (16 * scale).sp))
                if (size != null) {
                    Text(size, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (media.failed && file == null) {
                    TextButton(onClick = media.retry) {
                        Text(stringResource(R.string.dialog_instant_view_retry))
                    }
                }
            }
        }
    }
    block.caption?.let { InstantViewCaption(it, scale, query, onOpenUrl) }
}

internal fun documentAspectRatio(width: Int, height: Int): Float =
    if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.5f, 2.4f) else 16f / 9f

@Composable
internal fun InstantViewVideoPoster(
    thumb: File?,
    loading: Boolean,
    failed: Boolean,
    onPlay: () -> Unit,
    aspectRatio: Float = 16f / 9f,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onPlay),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            ProgressiveStill(
                thumb = thumb,
                image = null,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                failed = false,
                failedText = "",
                modifier = Modifier.fillMaxSize(),
            )
        }
        when {
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.dialog_media_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(stringResource(R.string.dialog_instant_view_retry), style = MaterialTheme.typography.labelSmall)
            }
            loading -> Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                MonogramCircularProgress(
                    size = 28.dp,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.24f),
                )
            }
            else -> Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.dialog_reply_video),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}
