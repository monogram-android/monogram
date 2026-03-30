package org.monogram.presentation.features.chats.currentChat.components.channels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.chats.*

@Composable
fun ChannelVoiceMessageBubble(
    content: MessageContent.Voice,
    msg: MessageModel,
    isSameSenderAbove: Boolean = false,
    isSameSenderBelow: Boolean = false,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float = 18f,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    autoDownloadFiles: Boolean,
    onVoiceClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    downloadUtils: IDownloadUtils
) {
    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp

    val bubbleShape = RoundedCornerShape(
        topStart = if (isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = cornerRadius,
        bottomStart = if (isSameSenderBelow) smallCorner else tailCorner,
        bottomEnd = cornerRadius
    )

    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = contentColor.copy(alpha = 0.7f)

    var bubblePosition by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(
        content.path,
        content.isDownloading,
        autoDownloadFiles,
        autoDownloadMobile,
        autoDownloadWifi,
        autoDownloadRoaming
    ) {
        val shouldDownload = if (autoDownloadFiles) {
            when {
                downloadUtils.isRoaming() -> autoDownloadRoaming
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                else -> autoDownloadMobile
            }
        } else {
            false
        }

        if (shouldDownload && content.path == null && !content.isDownloading) {
            onVoiceClick(msg)
        }
    }

    Column(
        modifier = modifier
            .onGloballyPositioned { bubblePosition = it.positionInWindow() },
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                    .fillMaxWidth()
            ) {
                msg.forwardInfo?.let { forward ->
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        ForwardContent(forward, false, onForwardClick = toProfile)
                    }
                }
                msg.replyToMsg?.let { reply ->
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = false,
                            onClick = { onReplyClick(reply) }
                        )
                    }
                }

                VoiceRow(
                    content = content,
                    msg = msg,
                    onVoiceClick = onVoiceClick,
                    onCancelDownload = onCancelDownload,
                    isOutgoing = false
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    MessageMetadata(msg, false, timeColor)
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

        MessageReactionsView(
            reactions = msg.reactions,
            onReactionClick = onReactionClick,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
