package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.monogram.core.common.Outcome
import org.monogram.core.models.PeerId
import org.monogram.core.models.SavedGif
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.gzipFile
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingInlineSize
import org.monogram.core.ui.mp4File
import org.monogram.core.ui.webmFile
import org.monogram.feature.dialog.GifCellPlayer
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.gifCellPlayer
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.mediaThumbCacheKey
import java.io.File

@Composable
internal fun SavedGifCell(
    gif: SavedGif,
    mediaRepository: MediaRepository?,
    onClick: () -> Unit,
    thumbOnly: Boolean = false,
    panelLoop: Boolean? = null,
) {
    val animationEnabled = LocalMediaAnimationEnabled.current
    val (measuredVisible, visibilityModifier) = rememberViewportVisible("gif:${gif.documentId}")
    val visible = if (panelLoop == null) measuredVisible else panelLoop
    val thumbKey = gif.thumbCacheKey ?: mediaThumbCacheKey(gif.cacheKey)
    val cacheChanges = remember(mediaRepository, gif.cacheKey, thumbKey) {
        mediaRepository?.cacheGeneration(listOf(gif.cacheKey, thumbKey))
    }
    val cacheGeneration = cacheChanges?.collectAsState(initial = 0L)?.value ?: 0L
    var file by remember(gif.documentId) { mutableStateOf<File?>(null) }
    var fullLoaded by remember(gif.documentId) { mutableStateOf(false) }
    var failed by remember(gif.documentId) { mutableStateOf(false) }
    var unavailable by remember(gif.documentId) { mutableStateOf(false) }
    var retry by remember(gif.documentId) { mutableStateOf(0) }
    val animate = animationEnabled && visible && !thumbOnly

    LaunchedEffect(cacheGeneration, animate) {
        val repo = mediaRepository ?: return@LaunchedEffect
        val full = repo.cachedFile(gif.cacheKey)?.takeIf { it.exists() && it.length() > 0L }
        if (full != null) {
            file = full
            fullLoaded = true
        } else if (file == null) {
            repo.cachedFile(thumbKey)?.takeIf { it.exists() && it.length() > 0L }?.let { file = it }
        }
    }

    LaunchedEffect(mediaRepository, gif.cacheKey, thumbKey, visible, retry) {
        val repo = mediaRepository ?: return@LaunchedEffect
        if (file != null || !visible) return@LaunchedEffect
        when (val thumb = repo.ensureIndexedMedia(
            PeerId(gif.documentId), 0, thumbKey,
            thumb = true, priority = MediaPriority.DEFAULT, mediaKind = "gif",
        )) {
            is Outcome.Ok -> if (!fullLoaded) file = thumb.value
            is Outcome.Err -> if (thumbOnly) failed = true
        }
    }

    // Full loading must not wait for an absent or slow Telegram thumbnail.
    LaunchedEffect(mediaRepository, gif.cacheKey, animate, retry) {
        val repo = mediaRepository ?: return@LaunchedEffect
        if (!animate) return@LaunchedEffect
        failed = false
        unavailable = false
        val cached = repo.cachedFile(gif.cacheKey)
        if (cached != null) {
            file = cached
            fullLoaded = true
            return@LaunchedEffect
        }
        repeat(3) { attempt ->
            when (val result = repo.ensureIndexedMedia(
                PeerId(gif.documentId), 0, gif.cacheKey,
                thumb = false, priority = MediaPriority.VISIBLE, mediaKind = "gif",
            )) {
                is Outcome.Ok -> {
                    file = result.value
                    fullLoaded = true
                    failed = false
                    return@LaunchedEffect
                }
                is Outcome.Err -> {
                    if (attempt < 2) delay(400L * (attempt + 1))
                    else failed = true
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .size(PickerMetrics.GifCell.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(visibilityModifier)
            .clickable {
                if (failed) retry++ else onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            file == null && !unavailable && !failed ->
                MonogramLoading(size = MonogramLoadingInlineSize)

            failed -> Text(
                text = stringResource(R.string.dialog_retry_thumb),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        file?.let { local ->
            val playableFile = fullLoaded &&
                    mediaRepository?.cachedFile(gif.cacheKey)?.let { cached ->
                        cached.exists() && cached.absolutePath == local.absolutePath
                    } == true
            when (gifCellPlayer(webmFile(local), mp4File(local), gzipFile(local), playableFile)) {
                GifCellPlayer.Video -> VideoPlayer(
                    file = local,
                    isGif = true,
                    durationSeconds = null,
                    compact = true,
                    active = animate,
                    onClick = onClick,
                    modifier = Modifier.fillMaxSize(),
                )

                GifCellPlayer.Tgs -> {
                    val bytes by produceState<ByteArray?>(initialValue = null, local) {
                        value = withContext(Dispatchers.IO) {
                            runCatching { local.readBytes() }.getOrNull()
                        }
                    }
                    if (bytes != null) {
                        StickerPlayer(
                            lottieBytes = bytes!!,
                            modifier = Modifier.fillMaxSize(),
                            displaySize = PickerMetrics.GifCell.dp,
                            active = animate,
                        )
                    }
                }

                GifCellPlayer.Still -> VideoStill(
                    file = local,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )

                GifCellPlayer.Image -> AsyncImage(
                    model = local,
                    contentDescription = stringResource(R.string.dialog_saved_gifs),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
private fun missingThumb(message: String): Boolean {
    val text = message.lowercase()
    return text.contains("no downloadable thumb") ||
        text.contains("file_id_invalid") ||
        text.contains("provided file id is invalid")
}

private fun deferredThumb(message: String): Boolean {
    val text = message.lowercase()
    return text == "media deferred" ||
        text.contains("file_migrate") ||
        text.contains("stored in dc")
}