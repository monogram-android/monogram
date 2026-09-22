package org.monogram.feature.dialog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.monogram.core.common.Outcome
import org.monogram.core.models.InstantViewPages
import org.monogram.core.models.PeerId
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import java.io.File

internal data class IvMediaFiles(
    val thumb: File? = null,
    val image: File? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val bytes: Long = 0L,
    val cacheKey: String = "",
    val retry: () -> Unit = {},
    val requestFull: () -> Unit = {},
    val fullLoading: Boolean = false,
)

@Composable
internal fun InstantViewPhoto(
    cacheKey: String,
    width: Int,
    height: Int,
    mediaRepository: MediaRepository?,
    onClick: (() -> Unit)? = null,
    hero: Boolean = false,
    openable: Boolean = false,
) {
    val media = rememberIvMedia(cacheKey, mediaRepository)
    val openMedia = LocalInstantViewMediaOpen.current
    val ratio = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
    val sharp = media.image?.takeIf { stillImageFile(it) }
    val thumb = media.thumb?.takeIf { stillImageFile(it) }
    val onTap = onClick ?: if (openable) ({ openMedia(cacheKey) }) else null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.5f, 2.2f))
            .then(if (hero) Modifier else Modifier.clip(RoundedCornerShape(12.dp)))
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        ProgressiveStill(
            thumb = thumb,
            image = sharp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            failed = false,
            failedText = "",
            loading = false,
            modifier = Modifier.fillMaxSize(),
        )
        if (media.failed && thumb == null && sharp == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.dialog_media_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = media.retry) {
                    Text(stringResource(R.string.dialog_instant_view_retry))
                }
            }
        } else if (media.loading && sharp == null) {
            DownloadCancelBadge(
                bytes = media.bytes,
                total = null,
                modifier = Modifier
                    .size(48.dp)
                    .clickable {
                        mediaRepository?.cancel(cacheKey)
                        mediaRepository?.cancel("$cacheKey:thumb")
                    },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.28f),
                scrim = Color.Black.copy(alpha = 0.28f),
            )
        }
    }
}

@Composable
internal fun rememberIvMedia(
    cacheKey: String,
    mediaRepository: MediaRepository?,
    autoFetchFull: Boolean = true,
    fullPriority: Int = MediaPriority.VISIBLE,
): IvMediaFiles {
    val parsed = remember(cacheKey) { InstantViewPages.parseMediaKey(cacheKey) }
    val thumbKey = remember(cacheKey) { "$cacheKey:thumb" }
    val generationFlow = remember(mediaRepository) {
        mediaRepository?.cacheGeneration ?: MutableStateFlow(0L)
    }
    val progressFlow = remember(mediaRepository) {
        mediaRepository?.downloadProgress ?: MutableStateFlow(emptyMap())
    }
    val generation by generationFlow.collectAsStateWithLifecycle()
    val progressMap by progressFlow.collectAsStateWithLifecycle()
    var thumb by remember(cacheKey) { mutableStateOf(mediaRepository?.cachedFile(thumbKey)) }
    var image by remember(cacheKey) { mutableStateOf(mediaRepository?.cachedFile(cacheKey)) }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    var fetchDone by remember(cacheKey) { mutableStateOf(image != null) }
    var retryToken by remember(cacheKey) { mutableIntStateOf(0) }
    var autoTries by remember(cacheKey) { mutableIntStateOf(0) }
    var wantFull by remember(cacheKey) { mutableStateOf(autoFetchFull) }
    LaunchedEffect(cacheKey, mediaRepository, generation, parsed, retryToken, wantFull) {
        val repo = mediaRepository
        val ref = parsed
        if (repo == null || ref == null) {
            fetchDone = true
            failed = ref == null && cacheKey.isNotBlank()
            return@LaunchedEffect
        }
        failed = false
        fetchDone = false
        repo.cachedFile(thumbKey)?.let { thumb = it }
        repo.cachedFile(cacheKey)?.let { image = it }
        if (image == null && thumb == null) {
            when (
                val fetched = repo.ensureIndexedMedia(
                    PeerId(ref.id),
                    ref.messageId,
                    thumbKey,
                    thumb = true,
                    priority = MediaPriority.VISIBLE,
                )
            ) {
                is Outcome.Ok -> thumb = fetched.value
                is Outcome.Err -> when (fetched.message) {
                    "cancelled" -> return@LaunchedEffect
                    "no avatar" -> {
                        fetchDone = true
                        failed = false
                        return@LaunchedEffect
                    }
                }
            }
        }
        if (image == null && wantFull) {
            var tries = 0
            while (image == null && tries < 3) {
                if (tries > 0) delay(400L * tries)
                when (
                    val fetched = repo.ensureIndexedMedia(
                        PeerId(ref.id),
                        ref.messageId,
                        cacheKey,
                        priority = fullPriority,
                    )
                ) {
                    is Outcome.Ok -> image = fetched.value
                    is Outcome.Err -> {
                        if (fetched.message == "cancelled") return@LaunchedEffect
                        tries += 1
                    }
                }
            }
        }
        val miss = image == null && thumb == null
        failed = miss && (wantFull || autoFetchFull)
        fetchDone = true
        if (failed && autoTries < 4) {
            delay(1_500L * (autoTries + 1))
            autoTries += 1
            retryToken += 1
        }
    }
    return IvMediaFiles(
        thumb = thumb,
        image = image,
        loading = !fetchDone && !failed,
        failed = failed,
        bytes = progressMap[cacheKey] ?: progressMap[thumbKey] ?: 0L,
        cacheKey = cacheKey,
        retry = {
            autoTries = 0
            failed = false
            fetchDone = false
            retryToken += 1
        },
        requestFull = {
            autoTries = 0
            failed = false
            wantFull = true
            retryToken += 1
        },
        fullLoading = wantFull && image == null && !failed,
    )
}
