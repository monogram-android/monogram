package org.monogram.presentation.features.chats.conversation.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.namespacedCacheKey
import org.monogram.presentation.features.chats.conversation.AutoDownloadSuppression
import org.monogram.presentation.features.chats.conversation.ui.message.BigEmojiContent
import org.monogram.presentation.features.chats.conversation.ui.message.ForwardContent
import org.monogram.presentation.features.chats.conversation.ui.message.MediaLoadingAction
import org.monogram.presentation.features.chats.conversation.ui.message.MessageMetadata
import org.monogram.presentation.features.chats.conversation.ui.message.MessageReactionsView
import org.monogram.presentation.features.chats.conversation.ui.message.MessageText
import org.monogram.presentation.features.chats.conversation.ui.message.ReplyContent
import org.monogram.presentation.features.chats.conversation.ui.message.StableMediaImage
import org.monogram.presentation.features.chats.conversation.ui.message.rememberMessageTextRenderData

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
    onClick: (Offset) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    showMetadata: Boolean = true,
    showReactions: Boolean = true,
    toProfile: (Long) -> Unit = {},
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
    modifier: Modifier = Modifier,
    downloadUtils: IDownloadUtils,
    animationsEnabled: Boolean = true
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

    LaunchedEffect(content.path) {
        if (!content.path.isNullOrBlank()) {
            stablePath = content.path
            isAutoDownloadSuppressed = false
            AutoDownloadSuppression.clear(content.fileId)
        }
    }

    val hasCaption = content.caption.isNotEmpty()
    val showCaptionAboveMedia = hasCaption && content.showCaptionAboveMedia

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
            ) {
                // Headers (Forward/Reply)
                if (msg.forwardInfo != null || msg.replyToMsg != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .zIndex(1f)
                    ) {
                        msg.forwardInfo?.let {
                            ForwardContent(
                                it,
                                false,
                                onForwardClick = onForwardOriginClick
                            )
                        }
                        msg.replyToMsg?.let { ReplyContent(it, false, onClick = { onReplyClick(it) }) }
                    }
                }

                @Composable
                fun CaptionSection() {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)
                            .zIndex(1f)
                    ) {
                        val renderData = rememberMessageTextRenderData(
                            text = content.caption,
                            entities = content.entities,
                            allowBigEmoji = false,
                            isOutgoing = false,
                            revealedSpoilers = revealedSpoilers,
                            fontSize = fontSize
                        )

                        if (renderData.isBigEmoji && renderData.bigEmojiItems.isNotEmpty()) {
                            BigEmojiContent(
                                items = renderData.bigEmojiItems,
                                sizeDp = fontSize * 5f
                            )
                        } else {
                            MessageText(
                                text = renderData.annotatedText,
                                rawText = content.caption,
                                inlineContent = renderData.inlineContent,
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
                                onClick = { offset -> onClick(imagePosition + offset) },
                                onLongClick = { offset -> onLongClick(imagePosition + offset) }
                            )
                        }

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
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = 0.8f
                                    )
                                )
                            }
                        }
                    }
                }

                if (showCaptionAboveMedia) {
                    CaptionSection()
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
                                when {
                                    !hasCaption -> bubbleShape
                                    showCaptionAboveMedia -> RoundedCornerShape(
                                        bottomStart = bottomStart,
                                        bottomEnd = if (showComments && msg.canGetMessageThread) 4.dp else bottomEnd
                                    )

                                    else -> RoundedCornerShape(
                                        topStart = topStart,
                                        topEnd = topEnd
                                    )
                                }
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
                            StableMediaImage(
                                previewModel = content.thumbnailPath ?: content.minithumbnail,
                                fullResolutionModel = stablePath,
                                cacheKey = photoCacheKey,
                                contentScale = ContentScale.Fit,
                                contentDescription = content.caption,
                                animationsEnabled = animationsEnabled,
                                modifier = Modifier.fillMaxSize()
                            )

                            if (!hasPath) {
                                MediaLoadingAction(
                                    isDownloading = content.isDownloading,
                                    progress = content.downloadProgress,
                                    idleIcon = Icons.Default.Download,
                                    idleContentDescription = stringResource(R.string.cd_download),
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
                                    .background(
                                        Color.Black.copy(alpha = 0.45f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                MessageMetadata(msg, msg.isOutgoing, Color.White)
                            }
                        }
                    }
                }

                // Caption Section
                if (hasCaption && !showCaptionAboveMedia) {
                    CaptionSection()
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
