package org.monogram.core.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.R

@Composable
fun OutgoingStatusMark(
    pending: Boolean,
    read: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
) {
    AnimatedContent(
        targetState = Triple(failed, pending, read),
        modifier = modifier,
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.72f)) togetherWith
                (fadeOut() + scaleOut(targetScale = 0.72f))
        },
        label = "outgoing-status",
    ) { (isFailed, isPending, isRead) ->
        if (isFailed) {
            Text(
                text = "!",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Icon(
                imageVector = when {
                    isPending -> Icons.Outlined.Schedule
                    isRead -> Icons.Outlined.DoneAll
                    else -> Icons.Outlined.Done
                },
                contentDescription = stringResource(
                    when {
                        isPending -> R.string.status_outgoing_sending
                        isRead -> R.string.status_outgoing_read
                        else -> R.string.status_outgoing_sent
                    },
                ),
                modifier = Modifier.size(16.dp),
                tint = if (isRead) MaterialTheme.colorScheme.primary else color,
            )
        }
    }
}
