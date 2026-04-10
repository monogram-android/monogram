package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.DateFormatManager
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MessageSearchItem(
    message: MessageModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val date = Date(message.date.toLong() * 1000)
    val calendar = Calendar.getInstance()
    val currentCalendar = Calendar.getInstance()
    calendar.time = date

    val dateFormatManager: DateFormatManager = koinInject();
    val timeFormat = dateFormatManager.getHourMinuteFormat()

    val isToday = calendar.get(Calendar.YEAR) == currentCalendar.get(Calendar.YEAR) &&
            calendar.get(Calendar.DAY_OF_YEAR) == currentCalendar.get(Calendar.DAY_OF_YEAR)

    val format = if (isToday) {
        SimpleDateFormat(timeFormat, Locale.getDefault())
    } else {
        SimpleDateFormat("MMM d", Locale.getDefault())
    }
    val time = format.format(date)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(
            path = message.senderAvatar,
            fallbackPath = message.senderPersonalAvatar,
            name = message.senderName,
            size = 48.dp
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = time,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))

            val text = when (val content = message.content) {
                is MessageContent.Text -> content.text
                is MessageContent.Photo -> content.caption.ifEmpty { "Photo" }
                is MessageContent.Video -> content.caption.ifEmpty { "Video" }
                is MessageContent.Voice -> "Voice message"
                is MessageContent.VideoNote -> "Video message"
                is MessageContent.Document -> content.caption.ifEmpty { content.fileName }
                is MessageContent.Sticker -> "Sticker ${content.emoji}"
                is MessageContent.Gif -> content.caption.ifEmpty { "GIF" }
                is MessageContent.Poll -> "Poll: ${content.question}"
                is MessageContent.Service -> content.text
                else -> "Message"
            }

            Text(
                text = text.replace("\n", " "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}