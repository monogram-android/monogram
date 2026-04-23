package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.ForwardOriginType
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.DateFormatManager

@Composable
fun ForwardContent(
    forwardInfo: ForwardInfo,
    isOutgoing: Boolean,
    onForwardClick: (ForwardInfo) -> Unit = {}
) {
    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()
    val canOpen = remember(forwardInfo) {
        when (forwardInfo.originType) {
            ForwardOriginType.USER -> forwardInfo.fromId != 0L
            ForwardOriginType.CHANNEL ->
                forwardInfo.originChatId != null && forwardInfo.originMessageId != null

            else -> false
        }
    }
    val title = remember(forwardInfo.originType) {
        when (forwardInfo.originType) {
            ForwardOriginType.USER -> "Forwarded from"
            ForwardOriginType.CHANNEL -> "Reposted from"
            ForwardOriginType.CHAT -> "Forwarded from"
            ForwardOriginType.HIDDEN_USER -> "Forwarded from"
            ForwardOriginType.UNKNOWN -> "Forwarded from"
        }
    }
    val unavailableLabel = remember(forwardInfo.originType) {
        when (forwardInfo.originType) {
            ForwardOriginType.USER -> "Profile unavailable"
            ForwardOriginType.CHANNEL -> "Post unavailable"
            else -> "Source unavailable"
        }
    }
    val contentColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val accentColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
    }

    Row(
        modifier = Modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
            )
            .clickable(enabled = canOpen) { onForwardClick(forwardInfo) }
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .fillMaxWidth()
            .alpha(if (canOpen) 1f else 0.88f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .background(accentColor, RoundedCornerShape(99.dp))
        )

        Spacer(modifier = Modifier.width(8.dp))

        if (!forwardInfo.avatarPath.isNullOrBlank() || !forwardInfo.personalAvatarPath.isNullOrBlank()) {
            Avatar(
                path = forwardInfo.avatarPath,
                fallbackPath = forwardInfo.personalAvatarPath,
                name = forwardInfo.fromName,
                size = 24.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = contentColor.copy(alpha = 0.64f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (forwardInfo.date > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatTime(forwardInfo.date, timeFormat),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = contentColor.copy(alpha = 0.56f),
                        maxLines = 1
                    )
                }
            }

            Text(
                text = forwardInfo.fromName,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                ),
                color = if (canOpen) accentColor else contentColor.copy(alpha = 0.86f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!canOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = contentColor.copy(alpha = 0.56f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unavailableLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = contentColor.copy(alpha = 0.56f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
