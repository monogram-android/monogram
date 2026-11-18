package org.monogram.core.harmony

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ServiceCard(
    title: String,
    subtitle: String,
    glyph: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    badge: String? = null,
    onClick: () -> Unit = {},
) {
    val accent = tint ?: HarmonyTheme.palette.primary
    ImmersiveGlass(
        scope = HarmonyGlassScope.Content,
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(HarmonyTokens.Radius.card),
    ) {
        Row(Modifier.padding(HarmonyTokens.Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(HarmonyTokens.Size.avatar)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(glyph, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(HarmonyTokens.Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (badge != null) {
                Box(
                    Modifier
                        .size(HarmonyTokens.Size.tagHeight)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (HarmonyTheme.isDark) Color(0xFF02090C) else Color.White,
                    )
                }
            }
        }
    }
}

@Composable
fun ChatCard(
    title: String,
    preview: String,
    time: String,
    unread: String? = null,
    modifier: Modifier = Modifier,
) {
    val accent = HarmonyTheme.palette.primary
    ImmersiveGlass(
        scope = HarmonyGlassScope.Content,
        modifier = modifier,
        shape = RoundedCornerShape(HarmonyTokens.Radius.card),
    ) {
        Row(Modifier.padding(HarmonyTokens.Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.take(1),
                    style = MaterialTheme.typography.titleLarge,
                    color = accent,
                )
            }
            Spacer(Modifier.width(HarmonyTokens.Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(HarmonyTokens.Spacing.xs))
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (unread != null) {
                    Spacer(Modifier.height(HarmonyTokens.Spacing.sm))
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            unread,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (HarmonyTheme.isDark) Color(0xFF02090C) else Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    text: String,
    outgoing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = if (outgoing) {
        RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
    } else {
        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    }
    Box(
        modifier
            .padding(vertical = HarmonyTokens.Spacing.sm)
            .background(if (outgoing) colors.primaryContainer else colors.surfaceVariant, shape)
            .padding(HarmonyTokens.Spacing.lg, HarmonyTokens.Spacing.md),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (outgoing) colors.onPrimaryContainer else colors.onSurface,
        )
    }
}

@Composable
fun SettingSwitchCard(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ImmersiveGlass(
        scope = HarmonyGlassScope.Control,
        modifier = modifier,
        shape = RoundedCornerShape(HarmonyTokens.Radius.card),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(HarmonyTokens.Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
