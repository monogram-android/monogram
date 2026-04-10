package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.DateFormatManager


@Composable
fun TextMessageBubble(
    content: MessageContent.Text,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    isGroup: Boolean = false,
    bubbleRadius: Float = 18f,
    showLinkPreviews: Boolean = true,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onInstantViewClick: ((String) -> Unit)? = null,
    onYouTubeClick: ((String) -> Unit)? = null,
    onClick: (Offset) -> Unit = {},
    onLongClick: (Offset) -> Unit = {},
    showReactions: Boolean = true,
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp

    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()

    val bubbleShape = remember(isOutgoing, isSameSenderAbove, isSameSenderBelow, cornerRadius, smallCorner) {
        RoundedCornerShape(
            topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
            topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
            bottomStart = if (!isOutgoing) {
                if (isSameSenderBelow) smallCorner else tailCorner
            } else cornerRadius,
            bottomEnd = if (isOutgoing) {
                if (isSameSenderBelow) smallCorner else tailCorner
            } else cornerRadius
        )
    }

    val backgroundColor =
        if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = contentColor.copy(alpha = 0.7f)

    val revealedSpoilers = remember { mutableStateListOf<Int>() }

    Column(
        modifier = modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = 60.dp),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
                    .animateContentSize()
            ) {
                if (isGroup && !isOutgoing && !isSameSenderAbove) {
                    MessageSenderName(msg, toProfile = toProfile)
                }

                msg.forwardInfo?.let { forward ->
                    ForwardContent(forward, isOutgoing, onForwardClick = toProfile)
                }
                msg.replyToMsg?.let { reply ->
                    ReplyContent(
                        replyToMsg = reply,
                        isOutgoing = isOutgoing,
                        onClick = { onReplyClick(reply) }
                    )
                }

                val inlineContent = rememberMessageInlineContent(content.entities, fontSize)
                val finalAnnotatedString = buildAnnotatedMessageTextWithEmoji(
                    text = content.text,
                    entities = content.entities,
                    isOutgoing = isOutgoing,
                    revealedSpoilers = revealedSpoilers
                )

                val isBigEmoji = remember(content.text, content.entities) {
                    isBigEmoji(content.text, content.entities)
                }
                val finalFontSize = if (isBigEmoji) fontSize * 5f else fontSize

                AnimatedContent(
                    targetState = finalAnnotatedString,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                                scaleIn(initialScale = 0.97f, animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                                slideInVertically(initialOffsetY = { it / 8 }, animationSpec = tween(240, easing = FastOutSlowInEasing)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(180)) +
                                        scaleOut(targetScale = 0.99f, animationSpec = tween(180)) +
                                        slideOutVertically(targetOffsetY = { -it / 10 }, animationSpec = tween(180))
                            )
                    },
                    label = "TextEditAnimation"
                ) { targetText ->
                    MessageText(
                        text = targetText,
                        entities = content.entities,
                        inlineContent = inlineContent,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = finalFontSize.sp,
                            letterSpacing = letterSpacing.sp,
                            lineHeight = (finalFontSize * 1.1f).sp
                        ),
                        modifier = Modifier.padding(bottom = 2.dp),
                        isOutgoing = isOutgoing,
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
                }

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
                    if (msg.editDate > 0) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edited",
                            modifier = Modifier.size(14.dp),
                            tint = timeColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = formatTime(msg.date, timeFormat),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = timeColor
                    )

                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageSendingStatusIcon(
                            sendingState = msg.sendingState,
                            isRead = msg.isRead,
                            baseColor = timeColor,
                            size = 14.dp
                        )
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
