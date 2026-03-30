package org.monogram.presentation.features.instantview.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.domain.models.webapp.PageBlockCaption
import org.monogram.domain.models.webapp.RichText
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
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

    val scaledStyle = style.copy(
        fontSize = style.fontSize * textSizeMultiplier,
        lineHeight = if (!style.lineHeight.isUnspecified) style.lineHeight * textSizeMultiplier else style.lineHeight,
        color = color.takeOrElse { style.color.takeOrElse { MaterialTheme.colorScheme.onSurface } },
        textAlign = textAlign ?: TextAlign.Unspecified,
        fontWeight = fontWeight ?: style.fontWeight,
        fontStyle = fontStyle ?: style.fontStyle
    )

    ClickableText(
        text = annotatedString,
        style = scaledStyle,
        modifier = modifier,
        maxLines = maxLines,
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    val url = normalizeUrl(annotation.item)
                    onUrlClick(url)
                }
        }
    )
}

@Composable
fun AsyncImageWithDownload(
    path: String?,
    fileId: Int,
    minithumbnail: ByteArray? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val messageRepository = LocalMessageRepository.current
    var currentPath by remember(fileId) { mutableStateOf(path) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(path, fileId) {
        if (!path.isNullOrEmpty()) {
            currentPath = path
        }
        if (currentPath == null) {
            messageRepository.downloadFile(fileId)

            val progressJob = launch {
                messageRepository.messageDownloadProgressFlow
                    .filter { it.first == fileId.toLong() }
                    .collect { progress = it.second }
            }

            val completedPath = withTimeoutOrNull(60_000L) {
                messageRepository.messageDownloadCompletedFlow
                    .filter { it.first == fileId.toLong() }
                    .mapNotNull { (_, _, candidatePath) -> candidatePath.takeIf { it.isNotEmpty() } }
                    .first()
            }

            currentPath = completedPath ?: messageRepository.getFilePath(fileId)
            progressJob.cancel()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (currentPath != null) {
            AsyncImage(
                model = currentPath,
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
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
fun AsyncVideoWithDownload(
    path: String?,
    videoPlayerPool: VideoPlayerPool,
    fileId: Int,
    modifier: Modifier = Modifier,
    shouldLoop: Boolean = true,
    contentScale: ContentScale = ContentScale.Fit
) {
    val messageRepository = LocalMessageRepository.current
    var currentPath by remember(fileId) { mutableStateOf(path) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(path, fileId) {
        if (!path.isNullOrEmpty()) {
            currentPath = path
        }
        if (currentPath == null) {
            messageRepository.downloadFile(fileId)

            val progressJob = launch {
                messageRepository.messageDownloadProgressFlow
                    .filter { it.first == fileId.toLong() }
                    .collect { progress = it.second }
            }

            val completedPath = withTimeoutOrNull(60_000L) {
                messageRepository.messageDownloadCompletedFlow
                    .filter { it.first == fileId.toLong() }
                    .mapNotNull { (_, _, candidatePath) -> candidatePath.takeIf { it.isNotEmpty() } }
                    .first()
            }

            currentPath = completedPath ?: messageRepository.getFilePath(fileId)
            progressJob.cancel()
        }
    }

    if (currentPath != null) {
        VideoStickerPlayer(
            path = currentPath!!,
            type = VideoType.Gif,
            modifier = modifier,
            animate = true,
            shouldLoop = shouldLoop,
            contentScale = contentScale,
            videoPlayerPool = videoPlayerPool
        )
    } else {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .shimmerEffect(),
            contentAlignment = Alignment.Center
        ) {
            if (progress > 0f && progress < 1f) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
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
