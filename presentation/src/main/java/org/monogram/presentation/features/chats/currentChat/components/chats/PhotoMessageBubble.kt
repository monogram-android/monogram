package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.AutoDownloadSuppression

@Composable
fun PhotoMessageBubble(
    content: MessageContent.Photo,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    isGroup: Boolean = false,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    onPhotoClick: (MessageModel) -> Unit,
    onDownloadPhoto: (Int) -> Unit = {},
    onCancelDownload: (Int) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    showMetadata: Boolean = true,
    showReactions: Boolean = true,
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    downloadUtils: IDownloadUtils
) {
    val context = LocalContext.current
    val cornerRadius = 18.dp
    val smallCorner = 4.dp
    val tailCorner = 2.dp

    var stablePath by remember(msg.id, content.fileId) { mutableStateOf(content.path) }
    val hasPath = !stablePath.isNullOrBlank()
    var isFullImageReady by remember(msg.id, content.fileId) { mutableStateOf(false) }
    val mediaAlpha by animateFloatAsState(
        targetValue = if (hasPath && isFullImageReady) 1f else 0f,
        animationSpec = tween(320),
        label = "PhotoMediaAlpha"
    )

    LaunchedEffect(content.path, content.fileId) {
        if (!content.path.isNullOrBlank()) {
            stablePath = content.path
            AutoDownloadSuppression.clear(content.fileId)
        } else {
            stablePath = null
        }
    }

    LaunchedEffect(hasPath) {
        if (!hasPath) {
            isFullImageReady = false
        }
    }

    LaunchedEffect(content.path, content.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (content.path.isNullOrBlank() && !content.isDownloading && !AutoDownloadSuppression.isSuppressed(content.fileId)) {
            val shouldDownload = when {
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                downloadUtils.isRoaming() -> autoDownloadRoaming
                else -> autoDownloadMobile
            }
            if (shouldDownload) {
                onDownloadPhoto(content.fileId)
            }
        }
    }

    val topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius
    val topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius
    val bottomStart = if (!isOutgoing) {
        if (isSameSenderBelow) smallCorner else tailCorner
    } else cornerRadius
    val bottomEnd = if (isOutgoing) {
        if (isSameSenderBelow) smallCorner else tailCorner
    } else cornerRadius

    val bubbleShape = RoundedCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomEnd = bottomEnd,
        bottomStart = bottomStart
    )

    var imagePosition by remember { mutableStateOf(Offset.Zero) }
    val revealedSpoilers = remember { mutableStateListOf<Int>() }
    var isMediaSpoilerRevealed by remember { mutableStateOf(!content.hasSpoiler) }

    Column(
        modifier = modifier.width(IntrinsicSize.Max),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Column(modifier = Modifier
                .widthIn(max = 280.dp)
                .animateContentSize()) {
                if (isGroup && !isOutgoing && !isSameSenderAbove) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                            .zIndex(1f)
                    ) {
                        MessageSenderName(msg)
                    }
                }

                msg.forwardInfo?.let { forward ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .zIndex(1f)
                    ) {
                        ForwardContent(forward, isOutgoing, onForwardClick = toProfile)
                    }
                }
                msg.replyToMsg?.let { reply ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                            .zIndex(1f)
                    ) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = isOutgoing,
                            onClick = { onReplyClick(reply) }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp)
                        .aspectRatio(
                            if (content.width > 0 && content.height > 0)
                                (content.width.toFloat() / content.height.toFloat()).coerceIn(0.5f, 2f)
                            else 1f
                        )
                        .clipToBounds()
                        .onGloballyPositioned { imagePosition = it.positionInWindow() }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (content.hasSpoiler) {
                                        isMediaSpoilerRevealed = !isMediaSpoilerRevealed
                                    } else if (content.isDownloading) {
                                        AutoDownloadSuppression.suppress(content.fileId)
                                        onCancelDownload(content.fileId)
                                    } else {
                                        AutoDownloadSuppression.clear(content.fileId)
                                        if (hasPath) {
                                            onPhotoClick(msg)
                                        } else {
                                            onDownloadPhoto(content.fileId)
                                        }
                                    }
                                },
                                onLongPress = { offset -> onLongClick(imagePosition + offset) }
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        MediaLoadingBackground(
                            previewData = content.minithumbnail,
                            contentScale = ContentScale.Fit
                        )

                        if (hasPath) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(stablePath)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = content.caption,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = mediaAlpha },
                                contentScale = ContentScale.Fit,
                                onState = { state ->
                                    isFullImageReady = state is AsyncImagePainter.State.Success
                                }
                            )
                        }

                        if (!hasPath || !isFullImageReady) {
                            MediaLoadingAction(
                                isDownloading = content.isDownloading || hasPath,
                                progress = content.downloadProgress,
                                idleIcon = Icons.Default.Download,
                                idleContentDescription = "Download",
                                showCancelOnDownload = content.isDownloading,
                                onCancelClick = {
                                    AutoDownloadSuppression.suppress(content.fileId)
                                    onCancelDownload(content.fileId)
                                },
                                onIdleClick = {
                                    AutoDownloadSuppression.clear(content.fileId)
                                    if (hasPath) {
                                        onPhotoClick(msg)
                                    } else {
                                        onDownloadPhoto(content.fileId)
                                    }
                                }
                            )
                        }
                    }

                    if (content.isUploading) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (content.uploadProgress > 0f) {
                                CircularProgressIndicator(
                                    progress = { content.uploadProgress },
                                    color = Color.White,
                                    trackColor = Color.White.copy(alpha = 0.3f),
                                )
                            } else {
                                CircularProgressIndicator(
                                    color = Color.White
                                )
                            }
                        }
                    }

                    SpoilerWrapper(isRevealed = isMediaSpoilerRevealed) {
                        Box(modifier = Modifier.fillMaxSize())
                    }

                    if (content.caption.isEmpty() && showMetadata) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            MessageMetadata(msg, isOutgoing, Color.White)
                        }
                    }
                }

                if (content.caption.isNotEmpty()) {
                    val timeColor = if (isOutgoing)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
                            .zIndex(1f)
                    ) {
                        val inlineContent = rememberMessageInlineContent(content.entities, fontSize)
                        val finalAnnotatedString = buildAnnotatedMessageTextWithEmoji(
                            text = content.caption,
                            entities = content.entities,
                            isOutgoing = isOutgoing,
                            revealedSpoilers = revealedSpoilers
                        )

                        MessageText(
                            text = finalAnnotatedString,
                            inlineContent = inlineContent,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = fontSize.sp,
                                letterSpacing = letterSpacing.sp,
                                lineHeight = (fontSize * 1.375f).sp
                            ),
                            modifier = Modifier.padding(bottom = 4.dp),
                            onSpoilerClick = { index ->
                                if (revealedSpoilers.contains(index)) {
                                    revealedSpoilers.remove(index)
                                } else {
                                    revealedSpoilers.add(index)
                                }
                            },
                            onClick = { offset -> onLongClick(imagePosition + offset) },
                            onLongClick = { offset -> onLongClick(imagePosition + offset) }
                        )
                        if (showMetadata) {
                            Box(modifier = Modifier.align(Alignment.End)) {
                                MessageMetadata(msg, isOutgoing, timeColor)
                            }
                        }
                    }
                }
            }
        }

        if (showReactions) {
            MessageReactionsView(
                reactions = msg.reactions,
                onReactionClick = onReactionClick,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
