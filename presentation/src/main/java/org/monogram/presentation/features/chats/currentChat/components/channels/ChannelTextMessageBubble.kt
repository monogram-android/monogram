package org.monogram.presentation.features.chats.currentChat.components.channels

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.features.chats.currentChat.components.chats.*

@Composable
fun ChannelTextMessageBubble(
    content: MessageContent.Text,
    msg: MessageModel,
    isSameSenderAbove: Boolean = false,
    isSameSenderBelow: Boolean = false,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float,
    showLinkPreviews: Boolean = true,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onInstantViewClick: ((String) -> Unit)? = null,
    onYouTubeClick: ((String) -> Unit)? = null,
    onClick: (Offset) -> Unit = {},
    onLongClick: (Offset) -> Unit = {},
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    showReactions: Boolean = true,
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp

    val bubbleShape = RoundedCornerShape(
        topStart = if (isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = cornerRadius,
        bottomStart =
            if (isSameSenderBelow) smallCorner else tailCorner,
        bottomEnd = if (showComments && msg.canGetMessageThread) 4.dp else cornerRadius
    )

    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()

    val revealedSpoilers = remember { mutableStateListOf<Int>() }

    Column(
        modifier = modifier.widthIn(min = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = bubbleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
                    .animateContentSize()
            ) {
                msg.forwardInfo?.let { forward ->
                    ForwardContent(forward, false, onForwardClick = toProfile)
                }
                msg.replyToMsg?.let { reply ->
                    ReplyContent(
                        replyToMsg = reply,
                        isOutgoing = false,
                        onClick = { onReplyClick(reply) }
                    )
                }

                val inlineContent = rememberMessageInlineContent(content.entities, fontSize)
                val finalAnnotatedString = buildAnnotatedMessageTextWithEmoji(
                    text = content.text,
                    entities = content.entities,
                    isOutgoing = false,
                    revealedSpoilers = revealedSpoilers
                )

                val isBigEmoji = remember(content.text, content.entities) {
                    isBigEmoji(content.text, content.entities)
                }
                val finalFontSize = if (isBigEmoji) fontSize * 5f else fontSize

                MessageText(
                    text = finalAnnotatedString,
                    inlineContent = inlineContent,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = finalFontSize.sp,
                        letterSpacing = letterSpacing.sp,
                        lineHeight = (finalFontSize * 1.1f).sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    onSpoilerClick = { index ->
                        if (revealedSpoilers.contains(index)) {
                            revealedSpoilers.remove(index)
                        } else {
                            revealedSpoilers.add(index)
                        }
                    },
                    onClick = onClick,
                    onLongClick = onLongClick
                )

                if (showLinkPreviews) {
                    content.webPage?.let { webPage ->
                        LinkPreview(
                            webPage = webPage,
                            isOutgoing = msg.isOutgoing,
                            onInstantViewClick = onInstantViewClick,
                            onYouTubeClick = onYouTubeClick
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    msg.views?.let { viewsCount ->
                        if (viewsCount > 0) {
                            Icon(
                                imageVector = Icons.Outlined.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = formatViews(context, viewsCount),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                    Text(
                        text = formatTime(msg.date, timeFormat),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                    )

                    if (msg.isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        AnimatedContent(
                            targetState = msg.sendingState to msg.isRead,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                            },
                            label = "SendingState"
                        ) { (sendingState, isRead) ->
                            val statusIcon = when (sendingState) {
                                is MessageSendingState.Pending -> Icons.Default.Schedule
                                is MessageSendingState.Failed -> Icons.Default.Error
                                null -> if (isRead) Icons.Default.DoneAll else Icons.Default.Check
                            }
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = "Status",
                                modifier = Modifier.size(14.dp),
                                tint = if (sendingState is MessageSendingState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    0.6f
                                )
                            )
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
