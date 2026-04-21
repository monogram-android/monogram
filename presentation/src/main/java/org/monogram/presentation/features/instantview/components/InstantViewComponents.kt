package org.monogram.presentation.features.instantview.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.webapp.PageBlockCaption
import org.monogram.domain.models.webapp.RichText
import org.monogram.presentation.features.chats.currentChat.components.VideoStickerPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoType
import org.monogram.presentation.features.chats.currentChat.components.chats.normalizeUrl
import org.monogram.presentation.features.stickers.ui.view.shimmerEffect

@Composable
fun RichTextView(
    richText: RichText,
    style: TextStyle,
    textSizeMultiplier: Float,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    val onUrlClick = LocalOnUrlClick.current
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedString = renderRichText(richText, linkColor)
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val scaledStyle = style.copy(
        fontSize = style.fontSize * textSizeMultiplier,
        lineHeight = if (!style.lineHeight.isUnspecified) style.lineHeight * textSizeMultiplier else style.lineHeight,
        color = color.takeOrElse { style.color.takeOrElse { MaterialTheme.colorScheme.onSurface } },
        textAlign = textAlign ?: TextAlign.Unspecified,
        fontWeight = fontWeight ?: style.fontWeight,
        fontStyle = fontStyle ?: style.fontStyle
    )

    Text(
        text = annotatedString,
        style = scaledStyle,
        modifier = modifier.pointerInput(annotatedString, onUrlClick) {
            detectTapGestures { position ->
                val layout = textLayoutResult ?: return@detectTapGestures
                val offset = layout.getOffsetForPosition(position)

                when {
                    annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull() != null -> {
                        val annotation = annotatedString.getStringAnnotations(
                            tag = "URL",
                            start = offset,
                            end = offset
                        )
                            .first()
                        onUrlClick(normalizeUrl(annotation.item))
                    }

                    annotatedString.getStringAnnotations(
                        tag = "EMAIL",
                        start = offset,
                        end = offset
                    )
                        .firstOrNull() != null -> {
                        val annotation = annotatedString.getStringAnnotations(
                            tag = "EMAIL",
                            start = offset,
                            end = offset
                        )
                            .first()
                        onUrlClick("mailto:${annotation.item}")
                    }

                    annotatedString.getStringAnnotations(
                        tag = "PHONE",
                        start = offset,
                        end = offset
                    )
                        .firstOrNull() != null -> {
                        val annotation = annotatedString.getStringAnnotations(
                            tag = "PHONE",
                            start = offset,
                            end = offset
                        )
                            .first()
                        onUrlClick("tel:${annotation.item}")
                    }
                }
            }
        },
        maxLines = maxLines,
        onTextLayout = { textLayoutResult = it }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AsyncImageWithDownload(
    path: String?,
    fileId: Int,
    modifier: Modifier = Modifier,
    minithumbnail: ByteArray? = null,
    contentScale: ContentScale = ContentScale.Fit
) {
    val fileRepository = LocalFileRepository.current
    val context = LocalContext.current
    var currentPath by rememberSaveable(fileId) { mutableStateOf(path) }
    var progress by remember(fileId) { mutableFloatStateOf(0f) }

    LaunchedEffect(path, fileId) {
        progress = 0f
        if (!path.isNullOrEmpty()) {
            currentPath = path
        }
        if (currentPath.isNullOrEmpty()) {
            currentPath = fileRepository.getFilePath(fileId)
        }
        if (currentPath == null && fileId != 0) {
            fileRepository.downloadFile(fileId)

            val progressJob = launch {
                fileRepository.fileDownloadFlow
                    .filterIsInstance<FileDownloadEvent.Progress>()
                    .filter { it.fileId == fileId }
                    .collect { progress = it.progress }
            }

            val completedPath = withTimeoutOrNull(60_000L) {
                fileRepository.fileDownloadFlow
                    .filterIsInstance<FileDownloadEvent.Completed>()
                    .filter { it.fileId == fileId }
                    .mapNotNull { event -> event.path.takeIf { it.isNotEmpty() } }
                    .first()
            }

            currentPath = completedPath ?: fileRepository.getFilePath(fileId)
            progressJob.cancel()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (currentPath != null) {
            AsyncImage(
                model = remember(currentPath, fileId) {
                    ImageRequest.Builder(context)
                        .data(currentPath)
                        .memoryCacheKey("instant_image:$fileId:${currentPath.orEmpty()}")
                        .diskCacheKey(currentPath.orEmpty())
                        .build()
                },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            if (minithumbnail != null) {
                AsyncImage(
                    model = minithumbnail,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(10.dp),
                    contentScale = contentScale,
                    alpha = 0.5f
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shimmerEffect()
                )
            }

            if (progress > 0f && progress < 1f) {
                CircularWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                LoadingIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AsyncVideoWithDownload(
    path: String?,
    fileId: Int,
    modifier: Modifier = Modifier,
    shouldLoop: Boolean = true,
    animate: Boolean = true,
    volume: Float = 0f,
    startPositionMs: Long = 0L,
    onProgressUpdate: (Long) -> Unit = {},
    onDurationKnown: (Long) -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    reportProgress: Boolean = false,
    contentScale: ContentScale = ContentScale.Fit
) {
    val fileRepository = LocalFileRepository.current
    var currentPath by rememberSaveable(fileId) { mutableStateOf(path) }
    var progress by remember(fileId) { mutableFloatStateOf(0f) }

    LaunchedEffect(path, fileId) {
        progress = 0f
        if (!path.isNullOrEmpty()) {
            currentPath = path
        }
        if (currentPath.isNullOrEmpty()) {
            currentPath = fileRepository.getFilePath(fileId)
        }
        if (currentPath == null && fileId != 0) {
            fileRepository.downloadFile(fileId)

            val progressJob = launch {
                fileRepository.fileDownloadFlow
                    .filterIsInstance<FileDownloadEvent.Progress>()
                    .filter { it.fileId == fileId }
                    .collect { progress = it.progress }
            }

            val completedPath = withTimeoutOrNull(60_000L) {
                fileRepository.fileDownloadFlow
                    .filterIsInstance<FileDownloadEvent.Completed>()
                    .filter { it.fileId == fileId }
                    .mapNotNull { event -> event.path.takeIf { it.isNotEmpty() } }
                    .first()
            }

            currentPath = completedPath ?: fileRepository.getFilePath(fileId)
            progressJob.cancel()
        }
    }

    if (currentPath != null) {
        VideoStickerPlayer(
            path = currentPath!!,
            type = VideoType.Gif,
            modifier = modifier,
            animate = animate,
            shouldLoop = shouldLoop,
            volume = volume,
            startPositionMs = startPositionMs,
            contentScale = contentScale,
            onProgressUpdate = onProgressUpdate,
            onDurationKnown = onDurationKnown,
            onPlaybackEnded = onPlaybackEnded,
            reportProgress = reportProgress
        )
    } else {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .shimmerEffect(),
            contentAlignment = Alignment.Center
        ) {
            if (progress > 0f && progress < 1f) {
                CircularWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                LoadingIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
fun PageBlockCaptionView(caption: PageBlockCaption, textSizeMultiplier: Float, modifier: Modifier = Modifier) {
    val hasText = caption.text !is RichText.Plain || (caption.text as RichText.Plain).text.isNotEmpty()
    val hasCredit = caption.credit !is RichText.Plain || (caption.credit as RichText.Plain).text.isNotEmpty()

    if (hasText || hasCredit) {
        Column(modifier = modifier) {
            if (hasText) {
                RichTextView(
                    richText = caption.text,
                    style = MaterialTheme.typography.bodySmall,
                    textSizeMultiplier = textSizeMultiplier,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hasCredit) {
                RichTextView(
                    richText = caption.credit,
                    style = MaterialTheme.typography.labelSmall,
                    textSizeMultiplier = textSizeMultiplier,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
