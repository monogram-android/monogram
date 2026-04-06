package org.monogram.presentation.features.chats.currentChat.components.channels

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import org.monogram.presentation.core.util.namespacedCacheKey
import org.monogram.presentation.features.chats.currentChat.AutoDownloadSuppression
import org.monogram.presentation.features.chats.currentChat.components.chats.*

@Composable
fun ChannelPhotoMessageBubble(
    content: MessageContent.Photo,
    msg: MessageModel,
    isSameSenderAbove: Boolean = false,
    isSameSenderBelow: Boolean = false,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float = 18f,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    onPhotoClick: (MessageModel) -> Unit,
    onDownloadPhoto: (Int) -> Unit = {},
    onCancelDownload: (Int) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    showMetadata: Boolean = true,
    showReactions: Boolean = true,
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    downloadUtils: IDownloadUtils
) {
    val context = LocalContext.current
    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp

    // Corner definitions
    val topStart = if (isSameSenderAbove) smallCorner else cornerRadius
    val topEnd = cornerRadius
    val bottomStart = if (isSameSenderBelow) smallCorner else tailCorner
    val bottomEnd = cornerRadius

    val bubbleShape = RoundedCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomStart = bottomStart,
        bottomEnd = if (showComments && msg.canGetMessageThread) 4.dp else bottomEnd
    )

    var imagePosition by remember { mutableStateOf(Offset.Zero) }
    val revealedSpoilers = remember { mutableStateListOf<Int>() }

    var stablePath by remember(msg.id) { mutableStateOf(content.path) }
    val hasPath = !stablePath.isNullOrBlank()
    val photoCacheKey = remember(stablePath, content.fileId) {
        namespacedCacheKey("channel_photo:${content.fileId}", stablePath)
    }
    var isAutoDownloadSuppressed by remember(msg.id) { mutableStateOf(false) }
    var isFullImageReady by remember(msg.id) { mutableStateOf(false) }
    val mediaAlpha by animateFloatAsState(
        targetValue = if (hasPath && isFullImageReady) 1f else 0f,
        animationSpec = tween(320),
        label = "ChannelPhotoMediaAlpha"
    )

    LaunchedEffect(content.path) {
        if (!content.path.isNullOrBlank()) {
            stablePath = content.path
            isAutoDownloadSuppressed = false
            AutoDownloadSuppression.clear(content.fileId)
        }
    }

    LaunchedEffect(hasPath) {
        if (!hasPath) {
            isFullImageReady = false
        }
    }
    val hasCaption = content.caption.isNotEmpty()

    LaunchedEffect(content.path, content.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (content.path.isNullOrBlank() && !content.isDownloading && !isAutoDownloadSuppressed && !AutoDownloadSuppression.isSuppressed(
                content.fileId
            )
        ) {
            val shouldDownload = when {
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                downloadUtils.isRoaming() -> autoDownloadRoaming
                else -> autoDownloadMobile
            }
            if (shouldDownload) onDownloadPhoto(content.fileId)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()) {
                // Headers (Forward/Reply)
                if (msg.forwardInfo != null || msg.replyToMsg != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .zIndex(1f)
                    ) {
                        msg.forwardInfo?.let { ForwardContent(it, false, onForwardClick = toProfile) }
                        msg.replyToMsg?.let { ReplyContent(it, false, onClick = { onReplyClick(it) }) }
                    }
                }

                val mediaRatio = if (content.width > 0 && content.height > 0) {
                    (content.width.toFloat() / content.height.toFloat()).coerceIn(0.5f, 2f)
                } else 1f

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val mediaHeight = (maxWidth / mediaRatio).coerceIn(160.dp, 320.dp)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(mediaHeight)
                            .clip(
                                if (hasCaption) RoundedCornerShape(
                                    topStart = topStart,
                                    topEnd = topEnd
                                ) else bubbleShape
                            )
                            .clipToBounds()
                            .onGloballyPositioned { imagePosition = it.positionInWindow() }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        if (content.isDownloading) {
                                            isAutoDownloadSuppressed = true
                                            AutoDownloadSuppression.suppress(content.fileId)
                                            onCancelDownload(content.fileId)
                                        } else {
                                            isAutoDownloadSuppressed = false
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
                                        .apply {
                                            photoCacheKey?.let {
                                                memoryCacheKey(it)
                                                diskCacheKey(it)
                                            }
                                        }
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
                                        isAutoDownloadSuppressed = true
                                        AutoDownloadSuppression.suppress(content.fileId)
                                        onCancelDownload(content.fileId)
                                    },
                                    onIdleClick = {
                                        isAutoDownloadSuppressed = false
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

                        if (!hasCaption && showMetadata) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                MessageMetadata(msg, msg.isOutgoing, Color.White)
                            }
                        }
                    }
                }

                // Caption Section
                if (hasCaption) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)
                            .zIndex(1f)
                    ) {
                        val inlineContent = rememberMessageInlineContent(content.entities, fontSize)
                        val finalAnnotatedString = buildAnnotatedMessageTextWithEmoji(
                            text = content.caption,
                            entities = content.entities,
                            isOutgoing = false,
                            revealedSpoilers = revealedSpoilers
                        )

                        MessageText(
                            text = finalAnnotatedString,
                            inlineContent = inlineContent,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = fontSize.sp,
                                letterSpacing = letterSpacing.sp,
                                lineHeight = (fontSize * 1.35f).sp
                            ),
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
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                MessageMetadata(
                                    msg = msg,
                                    isOutgoing = msg.isOutgoing,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showComments && msg.canGetMessageThread) {

            ChannelCommentsButton(
                replyCount = msg.replyCount,
                bubbleRadius = bubbleRadius,
                isSameSenderBelow = isSameSenderBelow,
                onClick = { onCommentsClick(msg.id) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Reactions
        if (showReactions) {
            MessageReactionsView(
                reactions = msg.reactions,
                onReactionClick = onReactionClick,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .align(Alignment.Start)
            )
        }
    }
}
