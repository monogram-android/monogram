package org.monogram.feature.dialog.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import org.monogram.core.common.AppLog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import org.monogram.core.ui.loading.MonogramCircularProgress
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import org.monogram.core.models.Message
import org.monogram.core.ui.components.MediaPlaceholder
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.ui.loading.MonogramStateSwap

@Composable
internal fun DocumentBubble(
    file: File?,
    thumb: File? = null,
    name: String,
    size: Long?,
    loading: Boolean,
    failed: Boolean,
    downloadedBytes: Long = 0L,
    uploading: Boolean = false,
    onDownload: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRemoveCache: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openFailed by remember(file, name) { mutableStateOf(false) }
    val unknownSources = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val local = file ?: return@rememberLauncherForActivityResult
        if (needsUnknownSources(context, mimeFromName(name))) return@rememberLauncherForActivityResult
        scope.launch { openFailed = !openDownloadedFile(context, local, name) }
    }
    Row(
        modifier = modifier
            .widthIn(min = 220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                MediaTapModifier(
                    onTap = {
                        if (uploading) return@MediaTapModifier
                        if (loading && file == null) {
                            onCancel()
                            return@MediaTapModifier
                        }
                        if (file == null) {
                            onDownload()
                            return@MediaTapModifier
                        }
                        openFailed = false
                        val mime = mimeFromName(name)
                        if (needsUnknownSources(context, mime)) {
                            try {
                                unknownSources.launch(unknownSourcesIntent(context.packageName))
                            } catch (e: Exception) {
                                AppLog.warn("file", "failed to launch unknown sources settings: ${e.message}")
                                scope.launch { openFailed = !openDownloadedFile(context, file, name) }
                            }
                            return@MediaTapModifier
                        }
                        scope.launch { openFailed = !openDownloadedFile(context, file, name) }
                    },
                    onLongPress = onLongPress,
                ),
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            MonogramStateSwap(
                state = when {
                    loading && file == null && uploading -> "upload"
                    loading && file == null -> "download"
                    failed && file == null -> "failed"
                    thumb != null -> "thumb"
                    else -> "document"
                },
                label = "mediaBadge",
            ) { badge -> when (badge) {
                "upload" -> DownloadProgressIndicator(
                    bytes = 0L,
                    total = null,
                    modifier = Modifier.size(28.dp),
                    uploading = true,
                )
                "download" -> DownloadCancelBadge(
                    bytes = downloadedBytes,
                    total = size,
                )
                "failed" -> Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.dialog_media_failed),
                    tint = MaterialTheme.colorScheme.error,
                )
                "thumb" -> AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                else -> Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = stringResource(R.string.dialog_media_document),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sizeText = when {
                openFailed -> stringResource(R.string.dialog_media_open_failed)
                failed && file == null -> stringResource(R.string.dialog_media_failed)
                uploading && loading -> stringResource(R.string.dialog_media_uploading)
                loading && file == null -> formatDownloadProgress(downloadedBytes, size)
                else -> size?.takeIf { it > 0 }?.let(::formatFileSize)
            }
            sizeText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (openFailed || (failed && file == null)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (file != null) {
            IconButton(onClick = onRemoveCache) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.dialog_remove_cached_file),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun AudioBubble(
    file: File?,
    title: String,
    durationSeconds: Int?,
    voice: Boolean,
    loading: Boolean,
    failed: Boolean,
    downloadedBytes: Long = 0L,
    fileSize: Long? = null,
    onRequest: () -> Unit = {},
    onCancel: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var playing by remember(file) { mutableStateOf(false) }
    val player = remember(file) {
        file?.takeIf { it.exists() }?.let { local ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(local)))
                prepare()
            }
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }
    LaunchedEffect(playing, player) {
        val exo = player ?: return@LaunchedEffect
        if (playing) exo.play() else exo.pause()
    }
    if (failed && file == null) {
        MediaPlaceholder(
            failed = true,
            failedText = stringResource(R.string.dialog_media_failed),
            modifier = modifier,
        )
        return
    }
    Row(
        modifier = modifier
            .widthIn(min = 220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                MediaTapModifier(
                    onTap = {
                        if (loading && file == null) onCancel()
                        else if (player == null) onRequest()
                        else playing = !playing
                    },
                    onLongPress = onLongPress,
                ),
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading && file == null -> DownloadCancelBadge(
                    bytes = downloadedBytes,
                    total = fileSize,
                    modifier = Modifier.size(40.dp),
                )
                else -> Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (playing) R.string.dialog_media_pause else R.string.dialog_media_play,
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    if (loading && file == null) {
                        formatDownloadProgress(downloadedBytes, fileSize)
                    } else {
                        null
                    },
                    if (voice) stringResource(R.string.dialog_media_voice) else null,
                    durationSeconds?.takeIf { it > 0 }?.let(::formatMediaDuration),
                ).joinToString(" · ").ifBlank { stringResource(R.string.dialog_media_audio) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DownloadProgressIndicator(
    bytes: Long,
    total: Long?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
    uploading: Boolean = false,
) {
    val fraction = downloadProgressFraction(bytes, total)
    val description = when {
        uploading -> stringResource(R.string.dialog_media_uploading)
        fraction != null && total != null -> stringResource(
            R.string.dialog_media_download_progress,
            formatFileSize(bytes),
            formatFileSize(total),
        )
        else -> stringResource(R.string.dialog_media_loading)
    }
    var generation by remember { mutableIntStateOf(0) }
    var previousBytes by remember { mutableLongStateOf(bytes) }
    LaunchedEffect(bytes) {
        if (bytes < previousBytes) generation++
        previousBytes = bytes
    }
    MonogramCircularProgress(
        visible = true,
        modifier = modifier,
        progress = fraction?.let { { fraction } },
        generation = generation,
        color = color,
        trackColor = trackColor,
        status = description,
    )
}

@Composable
internal fun DownloadCancelBadge(
    bytes: Long,
    total: Long?,
    modifier: Modifier = Modifier.size(48.dp),
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
    scrim: Color? = null,
) {
    Box(
        modifier = modifier.then(
            if (scrim != null) Modifier.clip(CircleShape).background(scrim) else Modifier,
        ),
        contentAlignment = Alignment.Center,
    ) {
        DownloadProgressIndicator(
            bytes = bytes,
            total = total,
            modifier = Modifier.size(28.dp),
            color = color,
            trackColor = trackColor,
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.dialog_media_cancel),
            tint = color,
            modifier = Modifier.size(14.dp),
        )
    }
}
