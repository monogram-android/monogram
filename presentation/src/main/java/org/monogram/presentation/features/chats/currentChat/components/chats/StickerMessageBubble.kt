package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.StickerModel
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import org.monogram.presentation.features.stickers.ui.view.StickerSkeleton
import java.io.File

@OptIn(UnstableApi::class, ExperimentalFoundationApi::class)
@Composable
fun StickerMessageBubble(
    content: MessageContent.Sticker,
    msg: MessageModel,
    isOutgoing: Boolean,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onStickerClick: (Long) -> Unit = {},
    onLongClick: () -> Unit = {},
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        msg.forwardInfo?.let { forward ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .widthIn(max = 200.dp)
            ) {
                Box(modifier = Modifier.padding(8.dp)) {
                    ForwardContent(forward, isOutgoing = false, onForwardClick = toProfile)
                }
            }
        }
        msg.replyToMsg?.let { reply ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .widthIn(max = 200.dp)
            ) {
                ReplyContent(
                    replyToMsg = reply,
                    isOutgoing = false,
                    onClick = { onReplyClick(reply) }
                )
            }
        }

        Box(
            modifier = Modifier
                .size(190.dp)
                .combinedClickable(
                    onClick = {
                        onStickerClick(content.setId)
                    },
                    onLongClick = onLongClick
                )
        ) {
            val validPath = content.path?.takeIf { it.isNotBlank() && File(it).exists() }
            if (validPath != null) {
                val stickerModel = StickerModel(
                    id = content.id,
                    width = content.width,
                    height = content.height,
                    emoji = content.emoji,
                    path = validPath,
                    format = content.format
                )

                StickerImage(
                    path = stickerModel.path,
                    animate = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    StickerSkeleton(modifier = Modifier.matchParentSize())

                    if (content.isDownloading) {
                        CircularProgressIndicator(
                            progress = { content.downloadProgress },
                            modifier = Modifier.size(48.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.35f)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (msg.editDate > 0) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edited",
                        modifier = Modifier.size(12.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = formatTime(msg.date),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.White,
                )
                if (isOutgoing) {
                    Spacer(modifier = Modifier.width(4.dp))
                    MessageSendingStatusIcon(
                        sendingState = msg.sendingState,
                        isRead = msg.isRead,
                        baseColor = Color.White,
                        size = 12.dp,
                        usePrimaryForRead = false
                    )
                }
            }
        }

        MessageReactionsView(
            reactions = msg.reactions,
            onReactionClick = onReactionClick,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}
