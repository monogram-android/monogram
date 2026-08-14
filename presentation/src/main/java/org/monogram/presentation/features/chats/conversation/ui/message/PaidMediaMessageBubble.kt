package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.PaidMediaItem
import org.monogram.presentation.core.util.namespacedCacheKey

@Composable
fun PaidMediaMessageBubble(
    content: MessageContent.PaidMedia,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    onPhotoClick: (MessageModel) -> Unit,
    onVideoClick: (MessageModel) -> Unit,
    onOpenBuy: () -> Unit,
    onClick: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onLongClick: () -> Unit = {},
    onReplyClick: (MessageModel) -> Unit = {},
    toProfile: (Long) -> Unit = {},
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
    isGroup: Boolean = false,
    animationsEnabled: Boolean = true
) {
    val bubbleShape = RoundedCornerShape(18.dp)
    val revealedSpoilers = remember { mutableStateListOf<Int>() }
    Surface(
        shape = bubbleShape,
        color = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isGroup && !isOutgoing && !isSameSenderAbove) {
                MessageSenderName(msg, toProfile = toProfile)
            }
            msg.forwardInfo?.let {
                ForwardContent(
                    it,
                    isOutgoing,
                    onForwardClick = onForwardOriginClick
                )
            }
            msg.replyToMsg?.let { ReplyContent(it, isOutgoing, onClick = { onReplyClick(it) }) }

            if (content.caption.isNotBlank() && content.showCaptionAboveMedia) {
                MessageText(
                    text = buildAnnotatedMessageTextWithEmoji(
                        text = content.caption,
                        entities = content.entities,
                        isOutgoing = isOutgoing,
                        revealedSpoilers = revealedSpoilers
                    ),
                    rawText = content.caption,
                    inlineContent = rememberMessageInlineContent(content.entities, 16f),
                    style = MaterialTheme.typography.bodyMedium,
                    entities = content.entities,
                    onSpoilerClick = { index ->
                        if (!revealedSpoilers.remove(index)) revealedSpoilers.add(index)
                    },
                    onClick = onClick,
                    onLongClick = { onLongClick() }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                content.items.forEachIndexed { index, item ->
                    PaidMediaGridItem(
                        item = item,
                        locked = true,
                        animationsEnabled = animationsEnabled,
                        onClick = when (item) {
                            is PaidMediaItem.Photo -> {
                                { onPhotoClick(msg) }
                            }

                            is PaidMediaItem.Video -> {
                                { onVideoClick(msg) }
                            }

                            else -> onOpenBuy
                        }
                    )
                }
            }

            if (content.caption.isNotBlank() && !content.showCaptionAboveMedia) {
                MessageText(
                    text = buildAnnotatedMessageTextWithEmoji(
                        text = content.caption,
                        entities = content.entities,
                        isOutgoing = isOutgoing,
                        revealedSpoilers = revealedSpoilers
                    ),
                    rawText = content.caption,
                    inlineContent = rememberMessageInlineContent(content.entities, 16f),
                    style = MaterialTheme.typography.bodyMedium,
                    entities = content.entities,
                    onSpoilerClick = { index ->
                        if (!revealedSpoilers.remove(index)) revealedSpoilers.add(index)
                    },
                    onClick = onClick,
                    onLongClick = { onLongClick() }
                )
            }
        }
    }
}

@Composable
private fun PaidMediaGridItem(
    item: PaidMediaItem,
    locked: Boolean,
    animationsEnabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when (item) {
            is PaidMediaItem.Photo -> StableMediaImage(
                previewModel = item.thumbnailPath ?: item.minithumbnail,
                fullResolutionModel = item.path,
                cacheKey = remember(item.fileId, item.path) {
                    namespacedCacheKey("paid_photo:${item.fileId}", item.path)
                },
                contentScale = ContentScale.Crop,
                contentDescription = null,
                animationsEnabled = animationsEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            )

            is PaidMediaItem.Video -> StableMediaImage(
                previewModel = item.thumbnailPath ?: item.minithumbnail,
                fullResolutionModel = item.path ?: item.coverPath,
                cacheKey = remember(item.fileId, item.path, item.coverPath) {
                    namespacedCacheKey("paid_video:${item.fileId}", item.path ?: item.coverPath)
                },
                contentScale = ContentScale.Crop,
                contentDescription = null,
                animationsEnabled = animationsEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            )

            else -> Text("Paid media", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (locked) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    .padding(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
        if (item is PaidMediaItem.Video) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
