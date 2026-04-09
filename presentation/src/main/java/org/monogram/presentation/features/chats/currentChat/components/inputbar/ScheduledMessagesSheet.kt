package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.util.DateFormatManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessagesSheet(
    visible: Boolean,
    scheduledMessages: List<MessageModel>,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: (MessageModel) -> Unit,
    onDelete: (MessageModel) -> Unit,
    onSendNow: (MessageModel) -> Unit
) {
    if (!visible) return

    val scheduledMessagesSorted = remember(scheduledMessages) {
        scheduledMessages.sortedBy { it.date }
    }
    val nextScheduled = scheduledMessagesSorted.firstOrNull()
    val editableScheduledCount = remember(scheduledMessagesSorted) {
        scheduledMessagesSorted.count { canEditScheduledMessage(it) }
    }

    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.action_scheduled_messages),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = scheduledMessagesSorted.size.toString(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.scheduled_messages_summary_count,
                            scheduledMessagesSorted.size
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (nextScheduled != null) {
                            stringResource(
                                R.string.scheduled_messages_summary_next,
                                formatScheduledTimestamp(nextScheduled.date, timeFormat)
                            )
                        } else {
                            stringResource(R.string.scheduled_messages_empty)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            R.string.scheduled_messages_summary_editable,
                            editableScheduledCount
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (scheduledMessagesSorted.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.scheduled_messages_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(scheduledMessagesSorted, key = { _, message -> message.id }) { index, message ->
                            ScheduledMessageRow(
                                message = message,
                                onSendNow = { onSendNow(message) },
                                onEdit = { onEdit(message) },
                                onDelete = { onDelete(message) }
                            )
                            if (index < scheduledMessagesSorted.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(text = stringResource(R.string.action_refresh))
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(text = stringResource(R.string.action_done))
                }
            }
        }
    }
}

@Composable
private fun ScheduledMessageRow(
    message: MessageModel,
    onSendNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .fillMaxSize()
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = scheduledMessageTypeLabel(message).take(1),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val dateFormatManager: DateFormatManager = koinInject()
                Text(
                    text = formatScheduledTimestamp(message.date, dateFormatManager.getHourMinuteFormat()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = messagePreviewText(message),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.scheduled_message_id, message.id),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(6.dp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onSendNow, modifier = Modifier
            .weight(1f)
            .height(40.dp)) {
            Text(text = stringResource(R.string.action_send_now), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(
            onClick = onEdit,
            enabled = canEditScheduledMessage(message),
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
        ) {
            Text(text = stringResource(R.string.action_edit), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(onClick = onDelete, modifier = Modifier
            .weight(1f)
            .height(40.dp)) {
            Text(text = stringResource(R.string.action_delete_message), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun messagePreviewText(message: MessageModel): String =
    when (val content = message.content) {
        is MessageContent.Text -> content.text
        is MessageContent.Photo -> if (content.caption.isNotBlank()) content.caption else "Photo"
        is MessageContent.Video -> if (content.caption.isNotBlank()) content.caption else "Video"
        is MessageContent.Document -> if (content.caption.isNotBlank()) content.caption else "Document"
        is MessageContent.Gif -> if (content.caption.isNotBlank()) content.caption else "GIF"
        is MessageContent.Sticker -> "Sticker"
        is MessageContent.Voice -> "Voice message"
        is MessageContent.VideoNote -> "Video message"
        is MessageContent.Audio -> "Audio"
        is MessageContent.Location -> "Location"
        is MessageContent.Venue -> content.title
        is MessageContent.Contact -> listOf(content.firstName, content.lastName).filter { it.isNotBlank() }
            .joinToString(" ")

        is MessageContent.Service -> content.text
        is MessageContent.Poll -> content.question
        is MessageContent.Unsupported -> "Unsupported message"
        else -> "Message"
    }

private fun scheduledMessageTypeLabel(message: MessageModel): String =
    when (message.content) {
        is MessageContent.Text -> "Text"
        is MessageContent.Photo -> "Photo"
        is MessageContent.Video -> "Video"
        is MessageContent.Document -> "Document"
        is MessageContent.Gif -> "GIF"
        is MessageContent.Sticker -> "Sticker"
        is MessageContent.Voice -> "Voice"
        is MessageContent.VideoNote -> "Video message"
        else -> "Message"
    }

private fun canEditScheduledMessage(message: MessageModel): Boolean =
    when (message.content) {
        is MessageContent.Text -> true
        else -> false
    }
