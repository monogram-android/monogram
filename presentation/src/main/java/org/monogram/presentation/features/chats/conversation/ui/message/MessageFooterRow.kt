package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.MessageSendingState
import org.monogram.presentation.R

@Composable
internal fun MessageFooterRow(
    timeText: String,
    color: Color,
    isEdited: Boolean,
    isOutgoing: Boolean,
    isRead: Boolean,
    sendingState: MessageSendingState?,
    modifier: Modifier = Modifier,
    viewsText: String? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!viewsText.isNullOrEmpty()) {
            Icon(
                imageVector = Icons.Outlined.Visibility,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = viewsText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = color
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (isEdited) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.info_edited),
                modifier = Modifier.size(14.dp),
                tint = color
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = color
        )

        if (isOutgoing) {
            Spacer(modifier = Modifier.width(4.dp))
            MessageSendingStatusIcon(
                sendingState = sendingState,
                isRead = isRead,
                baseColor = color,
                size = 14.dp,
                usePrimaryForRead = false
            )
        }
    }
}
