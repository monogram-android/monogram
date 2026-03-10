package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel

@Composable
fun ReplyContent(
    replyToMsg: MessageModel,
    isOutgoing: Boolean,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
            )
            .clickable { onClick() }
            .padding(4.dp)
            .fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(32.dp)
                .background(
                    if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(2.dp)
                )
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column {
            Text(
                text = replyToMsg.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val (rawText, entities) = when (val content = replyToMsg.content) {
                is MessageContent.Text -> content.text to content.entities
                is MessageContent.Photo -> content.caption.ifEmpty { "Photo" } to content.entities
                is MessageContent.Video -> content.caption.ifEmpty { "Video" } to content.entities
                is MessageContent.Sticker -> "Sticker" to emptyList()
                is MessageContent.Voice -> "Voice message" to emptyList()
                is MessageContent.VideoNote -> "Video message" to emptyList()
                is MessageContent.Gif -> content.caption.ifEmpty { "GIF" } to content.entities
                is MessageContent.Document -> (content.caption.ifEmpty { content.fileName }) to content.entities
                else -> "Message" to emptyList()
            }

            val annotatedText = buildAnnotatedMessageTextWithEmoji(
                text = rawText,
                entities = entities
            )

            val inlineContent = rememberMessageInlineContent(
                entities = entities,
                fontSize = MaterialTheme.typography.bodySmall.fontSize.value
            )

            Text(
                text = annotatedText,
                inlineContent = inlineContent,
                style = MaterialTheme.typography.bodySmall,
                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
