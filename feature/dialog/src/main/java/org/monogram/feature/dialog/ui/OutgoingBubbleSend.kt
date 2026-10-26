package org.monogram.feature.dialog.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.database.toModel
import org.monogram.core.database.entity.OutgoingMediaSend
import org.monogram.core.database.entity.OutgoingSendPhase
import org.monogram.core.database.entity.OutgoingTextSendEntity
import org.monogram.core.models.Message
import org.monogram.feature.dialog.R

internal fun outgoingHistoryMessages(
    messages: List<Message>,
    text: List<OutgoingTextSendEntity>,
    media: List<OutgoingMediaSend>,
): List<Message> {
    val pending = text.filter { it.scheduleDate == null }.map { it.message } +
            media.filter { it.batch.scheduleDate == null }
                .flatMap { it.items.map { item -> item.message } }
    val ids = messages.mapTo(mutableSetOf()) { it.id.id }
    val randomIds = messages.mapNotNullTo(mutableSetOf()) { it.randomId?.takeIf { id -> id != 0L } }
    val missing = pending.filter { row ->
        row.id !in ids && (row.randomId == null || row.randomId !in randomIds)
    }.distinctBy { it.id }.map { it.toModel() }.sortedByDescending { it.date }
    return (missing + messages).sortedByDescending { it.date }
}

internal data class OutgoingBubbleSend(
    val id: Long,
    val phase: OutgoingSendPhase,
    val media: Boolean
)

internal fun outgoingBubbleSend(
    message: Message,
    text: List<OutgoingTextSendEntity>,
    media: List<OutgoingMediaSend>,
): OutgoingBubbleSend? {
    if (!message.outgoing) return null
    text.firstOrNull {
        it.scheduleDate == null && it.message.chatId == message.id.chatId.value &&
                (it.randomId == message.randomId || it.message.id == message.id.id)
    }?.let { return OutgoingBubbleSend(it.randomId, it.phase, false) }
    return media.firstOrNull { send ->
        send.batch.scheduleDate == null && send.batch.chatId == message.id.chatId.value &&
                send.items.any { it.message.id == message.id.id || it.randomId == message.randomId }
    }?.let { OutgoingBubbleSend(it.batch.batchId, it.batch.phase, true) }
}

@Composable
internal fun OutgoingBubbleFailure(uncertain: Boolean, error: String?, onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            text = listOfNotNull(
                stringResource(if (uncertain) R.string.dialog_outbox_uncertain else R.string.dialog_outbox_failed),
                error
            ).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(org.monogram.core.ui.R.string.status_retry))
        }
    }
}
