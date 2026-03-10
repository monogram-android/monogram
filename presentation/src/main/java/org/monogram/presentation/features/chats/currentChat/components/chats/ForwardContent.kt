package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.ForwardInfo

@Composable
fun ForwardContent(
    forwardInfo: ForwardInfo,
    isOutgoing: Boolean,
    onForwardClick: (Long) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
            )
            .clickable(enabled = forwardInfo.fromId != 0L) { onForwardClick(forwardInfo.fromId) }
            .padding(4.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(32.dp)
                .background(
                    if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    RoundedCornerShape(2.dp)
                )
        )

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Reply,
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer(scaleX = -1f), // Flip to look like forward
            tint = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Forwarded from",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = forwardInfo.fromName,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                ),
                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
