package org.monogram.presentation.features.chats.conversation.ui.channel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.features.chats.conversation.ui.message.BigEmojiContent
import org.monogram.presentation.features.chats.conversation.ui.message.ForwardContent
import org.monogram.presentation.features.chats.conversation.ui.message.LinkPreview
import org.monogram.presentation.features.chats.conversation.ui.message.LinkPreviewAction
import org.monogram.presentation.features.chats.conversation.ui.message.MessageFooterRow
import org.monogram.presentation.features.chats.conversation.ui.message.MessageReactionsView
import org.monogram.presentation.features.chats.conversation.ui.message.MessageText
import org.monogram.presentation.features.chats.conversation.ui.message.MessageTextLayoutInfo
import org.monogram.presentation.features.chats.conversation.ui.message.ReplyContent
import org.monogram.presentation.features.chats.conversation.ui.message.TextWithTimestampLayout
import org.monogram.presentation.features.chats.conversation.ui.message.rememberMessageTextRenderData
import org.monogram.presentation.features.chats.conversation.ui.message.shouldUseInlineFooter

@Composable
internal fun ChannelTextMessageBubble(
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
    onLinkPreviewAction: ((LinkPreviewAction) -> Unit)? = null,
    onLinkPreviewLongClick: (() -> Unit)? = null,
    onClick: (Offset) -> Unit = {},
    onLongClick: (Offset) -> Unit = {},
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    showReactions: Boolean = true,
    toProfile: (Long) -> Unit = {},
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
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
            ) {
                msg.forwardInfo?.let { forward ->
                    ForwardContent(forward, false, onForwardClick = onForwardOriginClick)
                }
                msg.replyToMsg?.let { reply ->
                    ReplyContent(
                        replyToMsg = reply,
                        isOutgoing = false,
                        onClick = { onReplyClick(reply) }
                    )
                }

                val renderData = rememberMessageTextRenderData(
                    text = content.text,
                    entities = content.entities,
                    isOutgoing = false,
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
                val footerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                val viewsText = msg.views
                    ?.takeIf { it > 0 }
                    ?.let { viewsCount -> formatViews(context, viewsCount) }
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
                        color = footerColor,
                        isEdited = msg.editDate > 0,
                        isOutgoing = msg.isOutgoing,
                        isRead = msg.isRead,
                        sendingState = msg.sendingState,
                        modifier = footerModifier,
                        viewsText = viewsText
                    )
                }

                if (renderData.isBigEmoji && renderData.bigEmojiItems.isNotEmpty()) {
                    BigEmojiContent(
                        items = renderData.bigEmojiItems,
                        sizeDp = finalFontSize,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp)
                    )
                } else if (useInlineTimestamp) {
                    TextWithTimestampLayout(
                        modifier = Modifier.fillMaxWidth(),
                        textLayoutInfo = textLayoutInfo,
                        textContent = {
                            MessageText(
                                text = renderData.annotatedText,
                                rawText = content.text,
                                entities = content.entities,
                                inlineContent = renderData.inlineContent,
                                style = messageTextStyle,
                                modifier = Modifier.fillMaxWidth(),
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
