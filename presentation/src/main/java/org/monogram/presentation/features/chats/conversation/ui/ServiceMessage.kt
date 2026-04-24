package org.monogram.presentation.features.chats.conversation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.ServiceEmphasis
import org.monogram.domain.models.ServiceKind

@Composable
fun ServiceMessage(service: MessageContent.Service, modifier: Modifier = Modifier) {
    val accent = serviceAccentColor(service.kind, service.emphasis)
    val icon = serviceIcon(service.kind)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(accent.copy(alpha = 0.12f))
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = service.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
                val subtitle = service.subtitle?.takeIf { it.isNotBlank() }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun serviceAccentColor(kind: ServiceKind, emphasis: ServiceEmphasis): Color {
    val colors = MaterialTheme.colorScheme
    if (emphasis == ServiceEmphasis.ERROR) return colors.error
    if (emphasis == ServiceEmphasis.WARNING) return colors.error.copy(alpha = 0.9f)
    if (emphasis == ServiceEmphasis.SUCCESS) return colors.tertiary
    return when (kind) {
        ServiceKind.SYSTEM -> colors.secondary
        ServiceKind.MEMBERSHIP -> colors.primary
        ServiceKind.CALL -> colors.tertiary
        ServiceKind.PAYMENT -> colors.primary
        ServiceKind.FORUM -> colors.tertiary
        ServiceKind.SECURITY -> colors.error
        ServiceKind.GIFT -> colors.primary
        ServiceKind.BOT -> colors.secondary
    }
}

private fun serviceIcon(kind: ServiceKind): ImageVector {
    return when (kind) {
        ServiceKind.SYSTEM -> Icons.Rounded.Info
        ServiceKind.MEMBERSHIP -> Icons.Rounded.PersonAddAlt1
        ServiceKind.CALL -> Icons.Rounded.Call
        ServiceKind.PAYMENT -> Icons.Rounded.Payments
        ServiceKind.FORUM -> Icons.Rounded.Forum
        ServiceKind.SECURITY -> Icons.Rounded.Security
        ServiceKind.GIFT -> Icons.Rounded.CardGiftcard
        ServiceKind.BOT -> Icons.Rounded.SmartToy
    }
}
