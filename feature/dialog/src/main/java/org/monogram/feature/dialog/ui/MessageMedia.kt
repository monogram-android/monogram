package org.monogram.feature.dialog.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Message
import org.monogram.core.models.WebpagePreview
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.components.MediaPlaceholder
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.localOutgoingFile
import org.monogram.network.http.MediaFetchKind
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.photoDisplayCacheKey
import java.io.File

@Composable
fun MessageMedia(
    message: Message,
    mediaRepository: MediaRepository?,
    modifier: Modifier = Modifier,
    fillBounds: Boolean = false,
    previewOnly: Boolean = false,
    edgeToEdge: Boolean = false,
    onStickerClick: (() -> Unit)? = null,
    onInstantView: ((WebpagePreview) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val kind = message.mediaKind ?: return
    val animationEnabled = LocalMediaAnimationEnabled.current
    if (kind == "unsupported") return
    val localFile = localOutgoingFile(message.mediaCacheKey)
        ?: localOutgoingFile(message.thumbCacheKey)
    if (localFile != null) {
        OutgoingLocalMedia(
            message = message,
            file = localFile,
            modifier = modifier,
            fillBounds = fillBounds,
            edgeToEdge = edgeToEdge,
            onLongPress = onLongPress,
        )
        return
    }
    val fullKey = message.mediaCacheKey ?: return
    val thumbKey = message.thumbCacheKey ?: fullKey
    val displayKey = if (shouldAutoFetchDisplayMedia(kind)) photoDisplayCacheKey(fullKey) else null
    val sticker = isStickerMedia(kind, message.text ?: message.fileName)
    val stickerSize = stickerDisplaySize(message.mediaWidth, message.mediaHeight)
    val stickerDp = maxOf(stickerSize.first, stickerSize.second)
    val stickerBox = Modifier.size(stickerSize.first.dp, stickerSize.second.dp)
    val photoSize = if (edgeToEdge) {
        bubbleEdgeMediaDisplaySize(kind, message.mediaWidth, message.mediaHeight)
    } else {
        visualMediaDisplaySize(kind, message.mediaWidth, message.mediaHeight)
    }
    val durationAtStart = edgeToEdge &&
        !shouldShowMessageCaption(message.mediaKind, message.text, message.fileName)
    val frameW by animateDpAsState(photoSize.first.dp, tween(220), label = "mediaFrameW")
    val frameH by animateDpAsState(photoSize.second.dp, tween(220), label = "mediaFrameH")
    val stickerClick = if (sticker && onStickerClick != null) {
        MediaTapModifier(onTap = onStickerClick, onLongPress = onLongPress)
    } else {
        Modifier
    }
    val (mediaVisible, visibilityModifier) = rememberViewportVisible(fullKey)
    val mediaAnimationEnabled = animationEnabled && mediaVisible
    val mediaModifier = if (sticker) {
        modifier.padding(vertical = 2.dp).then(stickerClick).then(visibilityModifier)
    } else if (fillBounds) {
        modifier.then(visibilityModifier)
    } else {
        modifier
            .size(frameW, frameH)
            .then(
                if (edgeToEdge) {
                    Modifier
                } else {
                    Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                },
            )
            .then(visibilityModifier)
    }
    var thumbFile by remember(thumbKey) {
        mutableStateOf(mediaRepository?.cachedFile(thumbKey))
    }
    var displayFile by remember(displayKey) {
        mutableStateOf(displayKey?.let { mediaRepository?.cachedFile(it) })
    }
    var fullFile by remember(fullKey) {
        mutableStateOf(mediaRepository?.cachedFile(fullKey))
    }
    var failed by remember(fullKey) { mutableStateOf(false) }
    var fullFailed by remember(fullKey) { mutableStateOf(false) }
    val openMedia = LocalOpenMessageMedia.current
    val albumMessages = LocalAlbumMessages.current
    var playing by remember(fullKey) { mutableStateOf(kind == "gif") }
    val mediaScope = rememberCoroutineScope()
    var wantFull by remember(fullKey) { mutableStateOf(false) }
    var downloadAttempt by remember(fullKey) { mutableStateOf(0) }
    var previewFetchDone by remember(fullKey) {
        mutableStateOf(displayFile != null || fullFile != null)
    }
    // Other downloads must not invalidate this message's media.
    val progress = remember(mediaRepository, fullKey) {
        mediaRepository?.downloadProgress
            ?.map { it[fullKey] ?: 0L }
            ?.distinctUntilChanged()
            ?: flowOf(0L)
    }
    val downloadedBytes by progress.collectAsStateWithLifecycle(initialValue = 0L)

    LaunchedEffect(
        thumbKey,
        displayKey,
        fullKey,
        mediaRepository,
        playing,
        wantFull,
        kind,
        downloadAttempt,
        previewOnly,
        mediaVisible,
    ) {
        val repo = mediaRepository ?: run {
            previewFetchDone = true
            return@LaunchedEffect
        }
        if (!mediaVisible && !wantFull) {
            return@LaunchedEffect
        }
        val displayPriority = MediaPriority.VISIBLE
        suspend fun fetch(kind: MediaFetchKind): File? = withContext(Dispatchers.IO) {
            var networkTries = 0
            repeat(16) {
                val result = when (kind) {
                    MediaFetchKind.Thumb ->
                        repo.ensureLocalMessageThumb(message, priority = MediaPriority.THUMB)
                    MediaFetchKind.Display ->
                        repo.ensureLocalMessageDisplay(message, priority = displayPriority)
                    MediaFetchKind.Full ->
                        repo.ensureLocalMessageMedia(message, priority = mediaFullPriority(wantFull))
                }
                when (result) {
                    is Outcome.Ok -> return@withContext result.value
                    is Outcome.Err -> {
                        if (result.message == "cancelled") return@withContext null
                        if (kind == MediaFetchKind.Display && result.message == "no display size") {
                            return@withContext null
                        }
                        val deferred = result.message == "media deferred"
                        val network = result.telegramError.kind == TelegramError.Kind.Network
                        val floodWait = result.telegramError.retryAfterSeconds
                            ?.takeIf { result.telegramError.kind == TelegramError.Kind.Flood }
                        if (deferred || floodWait != null || (network && networkTries < 2)) {
                            if (network) networkTries += 1
                            delay(
                                if (floodWait != null) {
                                    (floodWait.coerceAtLeast(1) + 1) * 1_000L
                                } else {
                                    400L
                                },
                            )
                        } else {
                            return@withContext null
                        }
                    }
                }
            }
            null
        }
        val waitingForSharp = displayFile == null && fullFile == null && (
            shouldAutoFetchDisplayMedia(kind) ||
                shouldAutoFetchFullMedia(kind, playing || wantFull)
            )
        if (!waitingForSharp) previewFetchDone = true
        coroutineScope {
            if (thumbFile == null && kind != "document" && kind != "audio" && kind != "voice") {
                launch {
                    thumbFile = fetch(MediaFetchKind.Thumb) ?: thumbFile
                }
            }
            if (shouldAutoFetchDisplayMedia(kind) && displayFile == null && fullFile == null) {
                displayFile = fetch(MediaFetchKind.Display)
            }
            val needFull = shouldAutoFetchFullMedia(kind, playing || wantFull)
            if (needFull && fullFile == null) {
                fullFailed = false
                fullFile = fetch(MediaFetchKind.Full)
                fullFailed = fullFile == null && (playing || wantFull)
                failed = fullFile == null && thumbFile == null && (playing || wantFull)
            }
        }
        previewFetchDone = true
    }

    val preview = when (kind) {
        "video" ->
            listOfNotNull(thumbFile, displayFile, fullFile).firstOrNull { stillImageFile(it) }
                ?: thumbFile ?: displayFile ?: fullFile
        "webpage" ->
            displayFile ?: thumbFile ?: fullFile
        else ->
            fullFile ?: displayFile ?: thumbFile
    }
    val stickerFile = fullFile ?: preview
    val webpage = remember(kind, message.fileName) {
        if (kind == "webpage") {
            org.monogram.core.models.WebpagePreviews.parse(message.fileName)
        } else {
            null
        }
    }
    val stickerBytes by produceState<ByteArray?>(initialValue = null, fullFile, preview, kind) {
        val file = fullFile ?: preview
        if (file != null && (kind == "sticker_animated" || gzipFile(file))) {
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            if (bytes != null) value = bytes
        }
    }
    when {
        kind == "webpage" -> {
            val stillThumb = thumbFile?.takeIf { stillImageFile(it) }
            val sharpStill = (displayFile ?: fullFile)?.takeIf { stillImageFile(it) }
            val placeholder = shouldBlurMediaPreview(thumbKey, fullKey, sharpStill != null)
            WebpagePreviewCard(
                preview = webpage,
                thumb = stillThumb,
                image = sharpStill ?: stillThumb.takeIf { !placeholder },
                loading = !previewFetchDone && !failed,
                onInstantView = onInstantView,
                onLongPress = onLongPress,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .then(visibilityModifier),
            )
        }
        sticker && webmFile(stickerFile) && stickerFile != null -> {
            VpxStickerPlayer(
                file = stickerFile,
                active = mediaAnimationEnabled,
                modifier = mediaModifier.then(stickerBox).then(
                    if (onStickerClick != null) {
                        Modifier.clickable(onClick = onStickerClick)
                    } else {
                        Modifier
                    },
                ),
            )
        }
        sticker && stickerBytes != null && gzipFile(fullFile ?: preview) -> {
            StickerPlayer(
                lottieBytes = stickerBytes!!,
                modifier = mediaModifier.then(stickerBox),
                displaySize = stickerDp.dp,
                active = mediaAnimationEnabled,
            )
        }
        kind == "gif" && playing && fullFile != null -> {
            Box(modifier = mediaModifier) {
                VideoPlayer(
                    file = fullFile!!,
                    isGif = true,
                    durationSeconds = message.mediaDuration,
                    active = mediaAnimationEnabled,
                    caption = message.text,
                    modifier = Modifier.fillMaxSize(),
                )
                if (!mediaAnimationEnabled) {
                    VideoStill(
                        file = fullFile!!,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        kind == "audio" || kind == "voice" -> {
            AudioBubble(
                onLongPress = onLongPress,
                file = fullFile,
                title = message.fileName ?: stringResource(
                    if (kind == "voice") R.string.dialog_media_voice else R.string.dialog_media_audio,
                ),
                durationSeconds = message.mediaDuration,
                voice = kind == "voice",
                loading = wantFull && fullFile == null && !fullFailed,
                failed = fullFailed,
                downloadedBytes = downloadedBytes,
                fileSize = message.fileSize,
                onRequest = { wantFull = true; playing = true },
                onCancel = {
                    wantFull = false
                    playing = false
                    mediaRepository?.cancel(fullKey)
                },
                modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
        !sticker && kind == "document" -> {
            DocumentBubble(
                onLongPress = onLongPress,
                file = fullFile,
                thumb = preview?.takeIf { stillImageFile(it) },
                name = message.fileName ?: message.text ?: stringResource(R.string.dialog_media_document),
                size = message.fileSize,
                loading = wantFull && fullFile == null && !fullFailed,
                failed = fullFailed,
                downloadedBytes = downloadedBytes,
                onDownload = {
                    fullFailed = false
                    wantFull = true
                    downloadAttempt += 1
                },
                onRemoveCache = {
                    wantFull = false
                    mediaScope.launch {
                        if (mediaRepository?.removeCachedFile(fullKey) == true) {
                            fullFile = null
                            if (thumbKey == fullKey) thumbFile = null
                            fullFailed = false
                            failed = false
                        }
                    }
                },
                onCancel = {
                    wantFull = false
                    mediaRepository?.cancel(fullKey)
                },
                modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
        kind == "video" || kind == "gif" -> {
            val loadingFull = playing && fullFile == null && !fullFailed
            val stillThumb = thumbFile?.takeIf { stillImageFile(it) }
            val sharpStill = (displayFile ?: fullFile)?.takeIf { stillImageFile(it) }
            val placeholder = shouldBlurMediaPreview(thumbKey, fullKey, sharpStill != null)
            VideoThumb(
                thumb = stillThumb,
                image = sharpStill,
                durationSeconds = if (kind == "video") message.mediaDuration else null,
                failed = failed || (playing && fullFailed),
                loading = loadingFull,
                previewLoading = shouldShowMediaPreviewSpinner(
                    placeholder,
                    previewFetchDone,
                    failed || (playing && fullFailed),
                ),
                downloadedBytes = downloadedBytes,
                fileSize = message.fileSize,
                onPlay = {
                    if (kind == "video") {
                        openMedia(message, albumMessages)
                    } else if (loadingFull) {
                        playing = false
                        mediaRepository?.cancel(fullKey)
                    } else {
                        fullFailed = false
                        playing = true
                    }
                },
                compact = fillBounds,
                durationAtStart = durationAtStart,
                onLongPress = onLongPress,
                modifier = mediaModifier,
            )
        }
        sticker || kind == "photo" -> {
            val description = stringResource(
                when {
                    sticker -> R.string.dialog_media_sticker
                    else -> R.string.dialog_media_photo
                },
            )
            val loadingFull = kind == "photo" && wantFull && fullFile == null && !fullFailed
            val stillThumb = thumbFile?.takeIf { stillImageFile(it) }
            val sharpStill = (fullFile ?: displayFile)?.takeIf { stillImageFile(it) }
            val placeholder = !sticker && shouldBlurMediaPreview(thumbKey, fullKey, sharpStill != null)
            val stillImage = sharpStill ?: stillThumb.takeIf { !placeholder }
            Box(
                modifier = mediaModifier.then(
                    if (sticker) stickerBox else Modifier
                ).then(
                    if (kind == "photo") {
                        MediaTapModifier(
                            onTap = { openMedia(message, albumMessages) },
                            onLongPress = onLongPress,
                        )
                    } else {
                        Modifier
                    },
                ),
                contentAlignment = Alignment.Center,
            ) {
                ProgressiveStill(
                    thumb = stillThumb,
                    image = stillImage,
                    contentDescription = description,
                    contentScale = if (sticker) ContentScale.Fit else ContentScale.Crop,
                    failed = failed && stillThumb == null && stillImage == null,
                    failedText = stringResource(R.string.dialog_media_failed),
                    loading = !loadingFull && shouldShowMediaPreviewSpinner(
                        placeholder,
                        previewFetchDone,
                        failed,
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
                MediaProgressOverlay(
                    visible = loadingFull,
                    bytes = downloadedBytes,
                    total = message.fileSize,
                )
            }
        }
        kind == "document" -> {
            if (fillBounds) {
                Box(
                    modifier = mediaModifier.background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
            } else {
                MediaPlaceholder(
                    failed = failed,
                    failedText = stringResource(R.string.dialog_media_failed),
                    modifier = modifier.padding(vertical = 4.dp),
                )
            }
        }
        else -> {
            Text(
                text = stringResource(R.string.dialog_media_document),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.padding(vertical = 4.dp),
            )
        }
    }
}
