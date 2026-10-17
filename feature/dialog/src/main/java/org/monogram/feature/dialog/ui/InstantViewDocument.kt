package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
    when (block.kind) {
        "video" -> InstantViewVideo(block, media, file)
        "audio" -> InstantViewAudio(block, media, scale, mediaRepository)
        else -> InstantViewFile(block, media, scale, mediaRepository)
    }
    block.caption?.let { InstantViewCaption(it, scale, query, onOpenUrl) }
}

@Composable
private fun InstantViewVideo(block: InstantViewBlock.Document, media: IvMediaFiles, file: File?) {
    val aspect = documentAspectRatio(block.width, block.height)
    val full = file?.takeIf { media.image != null }
    if (full != null && !stillImageFile(full)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(IvSpacing.CardCorner)),
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

@Composable
private fun InstantViewAudio(
    block: InstantViewBlock.Document,
    media: IvMediaFiles,
    scale: Float,
    mediaRepository: MediaRepository?,
) {
    val playing = media.image
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(IvSpacing.CardCorner))
            .background(ivCardColor())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaLeadButton(
                loading = media.loading && playing == null,
                loaded = playing != null,
                failed = media.failed,
                bytes = media.bytes,
                loadedIcon = Icons.Filled.MusicNote,
                idleIcon = Icons.Filled.PlayArrow,
                label = if (playing == null) {
                    stringResource(R.string.dialog_instant_view_media_load)
                } else {
                    stringResource(R.string.dialog_instant_view_media_ready)
                },
                onIdleClick = media.requestFull,
                onBusyClick = { mediaRepository?.cancel(block.cacheKey) },
                onRetryClick = media.retry,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = block.title ?: block.fileName
                    ?: stringResource(R.string.dialog_media_audio),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = (16 * scale).sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    block.performer?.takeIf { it.isNotBlank() },
                    block.durationSeconds?.takeIf { it > 0 }?.let(::formatMediaDuration),
                    InstantViewPages.formatFileSize(block.fileSize),
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        playing?.let {
            VideoPlayer(
                file = it,
                isGif = false,
                durationSeconds = block.durationSeconds,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                compact = true,
            )
        }
    }
}

@Composable
private fun InstantViewFile(
    block: InstantViewBlock.Document,
    media: IvMediaFiles,
    scale: Float,
    mediaRepository: MediaRepository?,
) {
    val context = LocalContext.current
    val name = block.fileName ?: stringResource(R.string.dialog_media_document)
    val size = InstantViewPages.formatFileSize(block.fileSize)
    val cached = media.image ?: media.thumb
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(IvSpacing.CardCorner))
            .background(ivCardColor())
            .clickable(enabled = !media.loading) {
                val local = cached
                if (local == null) {
                    media.requestFull()
                } else {
                    val mime = block.mimeType?.takeIf { it.isNotBlank() } ?: mimeFromName(name)
                    runCatching {
                        context.startActivity(
                            viewFileIntent(
                                context,
                                fileForExternalView(local, name),
                                mime
                            )
                        )
                    }
                }
            }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaLeadButton(
            loading = media.loading,
            loaded = cached != null,
            failed = media.failed,
            bytes = media.bytes,
            loadedIcon = Icons.Outlined.OpenInNew,
            idleIcon = Icons.Outlined.Download,
            label = if (cached == null) {
                stringResource(R.string.dialog_instant_view_media_download)
            } else {
                stringResource(R.string.dialog_instant_view_media_open)
            },
            onIdleClick = media.requestFull,
            onBusyClick = { mediaRepository?.cancel(block.cacheKey) },
            onRetryClick = media.retry,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = (16 * scale).sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(
                size,
                block.mimeType?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun MediaLeadButton(
    loading: Boolean,
    loaded: Boolean,
    failed: Boolean,
    bytes: Long,
    loadedIcon: ImageVector,
    idleIcon: ImageVector,
    label: String,
    onIdleClick: () -> Unit,
    onBusyClick: () -> Unit,
    onRetryClick: () -> Unit,
) {
    val downloading = loading && bytes > 0
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClickLabel = label) {
                when {
                    failed -> onRetryClick()
                    loading -> onBusyClick()
                    else -> onIdleClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading && downloading -> DownloadCancelBadge(
                bytes = bytes,
                total = null,
                modifier = Modifier.size(38.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
            )

            loading -> MonogramCircularProgress(
                size = 24.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            )

            failed -> Icon(
                Icons.Outlined.Download,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.error,
            )

            loaded -> Icon(
                loadedIcon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            else -> Icon(
                idleIcon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
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
            .clip(RoundedCornerShape(IvSpacing.CardCorner))
            .background(ivCardColor())
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
                TextButton(onClick = onPlay) {
                    Text(stringResource(R.string.dialog_instant_view_retry))
                }
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
