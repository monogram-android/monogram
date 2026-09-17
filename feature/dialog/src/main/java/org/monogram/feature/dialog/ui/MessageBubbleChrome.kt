package org.monogram.feature.dialog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.models.Message
import org.monogram.core.ui.components.OutgoingStatusMark
import org.monogram.feature.dialog.DialogTime
import org.monogram.feature.dialog.R
import java.time.ZoneId

@Composable
internal fun MessageMetadata(
    message: Message,
    time: String,
    color: Color,
    modifier: Modifier = Modifier,
    showReadStatus: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (message.editDate != null) {
            Text(
                text = stringResource(R.string.dialog_edited),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        messageStatusMark(message, showReadStatus)?.let { mark ->
            OutgoingStatusMark(
                pending = mark.pending,
                read = mark.double,
                failed = mark.failed,
                color = color,
            )
        }
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
internal fun senderTagLabel(raw: String?): String? = when {
    raw == null -> null
    raw == "role:admin" -> stringResource(R.string.dialog_admin)
    raw == "role:owner" -> stringResource(R.string.dialog_owner)
    raw == "role:bot" -> stringResource(R.string.dialog_bot)
    raw.startsWith("rank:") -> raw.removePrefix("rank:").takeIf { it.isNotBlank() }
    else -> raw.takeIf { it.isNotBlank() }
}

@Composable
internal fun messageTime(epochSeconds: Long): String {
    val context = LocalContext.current
    val use24Hour = android.text.format.DateFormat.is24HourFormat(context)
    return DialogTime.formatTime(
        epochSeconds = epochSeconds,
        zone = ZoneId.systemDefault(),
        locale = LocalLocale.current.platformLocale,
        use24Hour = use24Hour,
    )
}

internal fun messageShape(
    outgoing: Boolean,
    joinsMessageAbove: Boolean,
    joinsMessageBelow: Boolean,
): RoundedCornerShape {
    val cornerRadius = 18.dp
    val smallCorner = 4.dp
    val tailCorner = 2.dp
    return if (outgoing) {
        RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = if (joinsMessageAbove) smallCorner else cornerRadius,
            bottomStart = cornerRadius,
            bottomEnd = if (joinsMessageBelow) smallCorner else tailCorner,
        )
    } else {
        RoundedCornerShape(
            topStart = if (joinsMessageAbove) smallCorner else cornerRadius,
            topEnd = cornerRadius,
            bottomStart = if (joinsMessageBelow) smallCorner else tailCorner,
            bottomEnd = cornerRadius,
        )
    }
}

internal fun isStickerOnly(message: Message): Boolean {
    if (!isStickerMedia(message.mediaKind, message.text ?: message.fileName)) return false
    if (shouldShowMessageCaption(message.mediaKind, message.text, message.fileName)) return false
    return message.replyToMsgId == null &&
        message.fwdFrom.isNullOrBlank() &&
        message.viaBot.isNullOrBlank()
}

private data class StatusMark(
    val pending: Boolean = false,
    val double: Boolean = false,
    val failed: Boolean = false,
)

private fun messageStatusMark(message: Message, showReadStatus: Boolean): StatusMark? {
    if (!message.outgoing || !showReadStatus) return null
    return when {
        message.failed -> StatusMark(failed = true)
        message.pending || message.id.id <= 0 -> StatusMark(pending = true)
        message.read -> StatusMark(double = true)
        else -> StatusMark()
    }
}
