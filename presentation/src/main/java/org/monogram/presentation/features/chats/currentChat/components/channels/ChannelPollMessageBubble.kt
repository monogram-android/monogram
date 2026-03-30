package org.monogram.presentation.features.chats.currentChat.components.channels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.currentChat.components.chats.PollMessageBubble

@Composable
fun ChannelPollMessageBubble(
    content: MessageContent.Poll,
    msg: MessageModel,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float = 18f,
    onOptionClick: (Int) -> Unit,
    onRetractVote: () -> Unit = {},
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onShowVoters: (Int) -> Unit = {},
    onClosePoll: () -> Unit = {},
    onLongClick: (Offset) -> Unit = {},
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        PollMessageBubble(
            content = content,
            msg = msg,
            isOutgoing = false,
            isSameSenderAbove = isSameSenderAbove,
            isSameSenderBelow = isSameSenderBelow,
            fontSize = fontSize,
            letterSpacing = letterSpacing,
            bubbleRadius = bubbleRadius,
            onOptionClick = onOptionClick,
            onRetractVote = onRetractVote,
            onReplyClick = onReplyClick,
            onReactionClick = onReactionClick,
            onShowVoters = onShowVoters,
            onClosePoll = onClosePoll,
            onLongClick = onLongClick,
            toProfile = toProfile,
            modifier = Modifier.fillMaxWidth()
        )

        if (showComments && msg.canGetMessageThread) {
            ChannelCommentsButton(
                replyCount = msg.replyCount,
                bubbleRadius = bubbleRadius,
                isSameSenderBelow = isSameSenderBelow,
                onClick = { onCommentsClick(msg.id) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
