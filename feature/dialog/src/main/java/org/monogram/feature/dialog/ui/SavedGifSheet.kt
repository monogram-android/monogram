package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import org.monogram.core.ui.gzipFile
import org.monogram.core.ui.mp4File
import org.monogram.core.ui.webmFile
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.common.Outcome
import org.monogram.core.models.PeerId
import org.monogram.core.models.SavedGif
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import java.io.File
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingInlineSize
import org.monogram.core.ui.loading.MonogramStateSwap

@Composable
internal fun SavedGifCell(
    gif: SavedGif,
    mediaRepository: MediaRepository?,
    onClick: () -> Unit,
    thumbOnly: Boolean = false,
) {
    val animationEnabled = LocalMediaAnimationEnabled.current
    val (visible, visibilityModifier) = rememberViewportVisible(gif.documentId)
    var file by remember(gif.documentId) {
        mutableStateOf(mediaRepository?.cachedFile(gif.thumbCacheKey ?: gif.cacheKey))
    }
    var failed by remember(gif.documentId) { mutableStateOf(false) }
    var unavailable by remember(gif.documentId) { mutableStateOf(false) }
    var attempt by remember(gif.documentId) { mutableStateOf(0) }
    val wantFull = !thumbOnly && visible && animationEnabled
    LaunchedEffect(
        gif.documentId,
        gif.cacheKey,
        gif.thumbCacheKey,
        mediaRepository,
        attempt,
        wantFull,
    ) {
        val repo = mediaRepository ?: return@LaunchedEffect
        failed = false
        unavailable = false
        if (wantFull) {
            repo.cachedFile(gif.cacheKey)?.takeIf { it.exists() }?.let {
                file = it
                return@LaunchedEffect
            }
        }
        val key = gif.thumbCacheKey ?: gif.cacheKey
        repo.cachedFile(key)?.takeIf { it.exists() }?.let {
            file = it
            if (thumbOnly) return@LaunchedEffect
        }
        if (file == null) {
            repo.cachedFile("${gif.cacheKey}:thumb")?.takeIf { it.exists() }?.let {
                file = it
                if (thumbOnly) return@LaunchedEffect
            }
        }
        if (!wantFull && file != null) return@LaunchedEffect
        val thumbKey = gif.thumbCacheKey ?: "${gif.cacheKey}:thumb"
        suspend fun fetchThumb(): Outcome<File> {
            var last: Outcome<File> = Outcome.Err("no downloadable thumb")
            repeat(8) {
                last = repo.ensureIndexedMedia(
                    peerId = PeerId(gif.documentId),
                    messageId = 0,
                    cacheKey = thumbKey,
                    thumb = true,
                    priority = MediaPriority.VISIBLE,
                )
                when (last) {
                    is Outcome.Ok -> return last
                    is Outcome.Err -> {
                        val deferred = deferredThumb(last.message)
                        val missing = missingThumb(last.message)
                        if (missing) return last
                        if (deferred) delay(400) else return last
                    }
                }
            }
            return last
        }
        if (file == null) {
            when (val result = fetchThumb()) {
                is Outcome.Ok -> file = result.value
                is Outcome.Err -> {
                    if (!wantFull) {
                        unavailable = missingThumb(result.message) || deferredThumb(result.message)
                        failed = !unavailable
                        return@LaunchedEffect
                    }
                }
            }
        }
        if (wantFull) {
            repo.cachedFile(gif.cacheKey)?.takeIf { it.exists() }?.let {
                file = it
                return@LaunchedEffect
            }
            when (
                val full = repo.ensureIndexedMedia(
                    PeerId(gif.documentId),
                    0,
                    gif.cacheKey,
                    thumb = false,
                    priority = MediaPriority.VISIBLE,
                )
            ) {
                is Outcome.Ok -> file = full.value
                is Outcome.Err -> if (file == null) failed = true
            }
        }
    }
    val animate = animationEnabled && visible && !thumbOnly
    Box(
        modifier = Modifier
            .size(PickerMetrics.GifCell.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(visibilityModifier)
            .clickable(onClick = {
                if (failed) attempt += 1 else onClick()
            }),
        contentAlignment = Alignment.Center,
    ) {
        MonogramStateSwap(
            state = when {
                file != null || unavailable -> "ready"
                failed -> "failed"
                else -> "loading"
            },
            label = "gifThumb",
        ) { gifState -> when (gifState) {
            "ready" -> Unit
            "failed" -> Text(
                text = stringResource(R.string.dialog_retry_thumb),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
            else -> MonogramLoading(size = MonogramLoadingInlineSize)
        } }
        file?.let { local ->
            when {
                webmFile(local) || mp4File(local) -> VideoPlayer(
                    file = local,
                    isGif = true,
                    durationSeconds = null,
                    compact = true,
                    active = animate,
                    onClick = onClick,
                    modifier = Modifier.fillMaxSize(),
                )
                gzipFile(local) -> {
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
                else -> AsyncImage(
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
