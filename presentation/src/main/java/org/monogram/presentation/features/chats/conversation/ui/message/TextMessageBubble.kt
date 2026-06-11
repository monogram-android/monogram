package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.DateFormatManager

@Composable
internal fun TextMessageBubble(
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
    onLinkPreviewAction: ((LinkPreviewAction) -> Unit)? = null,
    onLinkPreviewLongClick: (() -> Unit)? = null,
    onClick: (Offset) -> Unit = {},
    onLongClick: (Offset) -> Unit = {},
    showReactions: Boolean = true,
    toProfile: (Long) -> Unit = {},
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
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
            ) {
                if (isGroup && !isOutgoing && !isSameSenderAbove) {
                    MessageSenderName(msg, toProfile = toProfile)
                }

                msg.forwardInfo?.let { forward ->
                    ForwardContent(forward, isOutgoing, onForwardClick = onForwardOriginClick)
                }
                msg.replyToMsg?.let { reply ->
                    ReplyContent(
                        replyToMsg = reply,
                        isOutgoing = isOutgoing,
                        onClick = { onReplyClick(reply) }
                    )
                }

                val renderData = rememberMessageTextRenderData(
                    text = content.text,
                    entities = content.entities,
                    isOutgoing = isOutgoing,
                    revealedSpoilers = revealedSpoilers,
                    fontSize = fontSize
                )

                val finalFontSize = if (renderData.isBigEmoji) fontSize * 5f else fontSize
                val messageTextStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = finalFontSize.sp,
                    letterSpacing = letterSpacing.sp,
                    lineHeight = (finalFontSize * 1.1f).sp
                )

                val hasLinkPreview = showLinkPreviews && content.webPage != null
                val hasReply = msg.replyToMsg != null
                val hasForward = msg.forwardInfo != null
                val useInlineTimestamp = shouldUseInlineFooter(
                    hasReply = hasReply,
                    hasForward = hasForward,
                    hasLinkPreview = hasLinkPreview,
                    isBigEmoji = renderData.isBigEmoji
                )
                val timeText = formatTime(msg.date, timeFormat)
                var textLayoutInfo by remember(
                    content.text,
                    content.entities,
                    finalFontSize,
                    letterSpacing,
                    hasReply,
                    hasForward,
                    useInlineTimestamp
                ) {
                    mutableStateOf<MessageTextLayoutInfo?>(null)
                }

                val footerRow: @Composable (Modifier) -> Unit = { footerModifier ->
                    MessageFooterRow(
                        timeText = timeText,
                        color = timeColor,
                        isEdited = msg.editDate > 0,
                        isOutgoing = isOutgoing,
                        isRead = msg.isRead,
                        sendingState = msg.sendingState,
                        modifier = footerModifier
                    )
                }

                if (renderData.isBigEmoji && renderData.bigEmojiItems.isNotEmpty()) {
                    BigEmojiContent(
                        items = renderData.bigEmojiItems,
                        sizeDp = finalFontSize,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                } else if (useInlineTimestamp) {
                    TextWithTimestampLayout(
                        textLayoutInfo = textLayoutInfo,
                        textContent = {
                            MessageText(
                                text = renderData.annotatedText,
                                rawText = content.text,
                                entities = content.entities,
                                inlineContent = renderData.inlineContent,
                                style = messageTextStyle,
                                isOutgoing = isOutgoing,
                                onSpoilerClick = { index ->
                                    if (revealedSpoilers.contains(index)) {
                                        revealedSpoilers.remove(index)
                                    } else {
                                        revealedSpoilers.add(index)
                                    }
                                },
                                onTextLayoutInfo = { textLayoutInfo = it },
                                onClick = onClick,
                                onLongClick = onLongClick
                            )
                        },
                        timestampContent = { footerRow(Modifier) }
                    )
                } else {
                    MessageText(
                        text = renderData.annotatedText,
                        rawText = content.text,
                        entities = content.entities,
                        inlineContent = renderData.inlineContent,
                        style = messageTextStyle,
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

                if (hasLinkPreview) {
                    content.webPage?.let { webPage ->
                        LinkPreview(
                            webPage = webPage,
                            isOutgoing = msg.isOutgoing,
                            onAction = { onLinkPreviewAction?.invoke(it) },
                            onLongClick = onLinkPreviewLongClick
                        )
                    }
                }

                if (!useInlineTimestamp) {
                    footerRow(Modifier.align(Alignment.End))
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
