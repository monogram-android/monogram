@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import org.koin.compose.koinInject
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.StickerModel
import org.monogram.presentation.R
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import org.monogram.presentation.features.stickers.ui.view.StickerSkeleton
import java.io.File

@OptIn(UnstableApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StickerMessageBubble(
    content: MessageContent.Sticker,
    msg: MessageModel,
    isOutgoing: Boolean,
    stickerSize: Float = 200f,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onStickerClick: (Long) -> Unit = {},
    onLongClick: () -> Unit = {},
    toProfile: (Long) -> Unit = {},
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()

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
                    ForwardContent(
                        forward,
                        isOutgoing = false,
                        onForwardClick = onForwardOriginClick
                    )
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
                .size(stickerSize.dp)
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
                        contentDescription = stringResource(R.string.info_edited),
                        modifier = Modifier.size(12.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = formatTime(msg.date, timeFormat),
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
