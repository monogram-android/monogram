package org.monogram.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val SettingsGroupCorner: Dp = 24.dp
val SettingsGroupGap: Dp = 8.dp
private val RowIconSize = 40.dp
private val RowHorizontalPadding = 16.dp
private val RowVerticalPadding = 12.dp
private val RowIconGap = 16.dp
private val DividerInset = RowHorizontalPadding + RowIconSize + RowIconGap

/** The one settings card chrome. Every group in settings is this, and nothing else. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    position: ItemPosition = ItemPosition.STANDALONE,
    selected: Boolean? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = SettingsGroupCorner,
            topEnd = SettingsGroupCorner,
        )
        ItemPosition.MIDDLE -> RoundedCornerShape(0.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = SettingsGroupCorner,
            bottomEnd = SettingsGroupCorner,
        )
        ItemPosition.STANDALONE -> RoundedCornerShape(SettingsGroupCorner)
    }
    val container by animateColorAsState(
        targetValue = if (selected == true) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "settingsCardContainer",
    )
    val contentColor = if (selected == true) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (position == ItemPosition.MIDDLE || position == ItemPosition.BOTTOM) {
            Box(
                modifier = Modifier
                    .padding(start = DividerInset)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            )
        }
        Surface(
            color = container,
            contentColor = contentColor,
            shape = shape,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .alpha(if (enabled) 1f else 0.5f)
                .then(
                    if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier,
                ),
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Column(content = content)
            }
        }
    }
}

/** A row inside a [SettingsCard]. Carries no surface of its own. */
@Composable
fun SettingsCardRow(
    icon: ImageVector,
    title: String,
    iconColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
    toggle: Boolean? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    // A switch row is toggled by the whole row, not just the switch: tapping the title has to work.
    val interaction = when {
        toggle != null && onToggle != null -> Modifier.toggleable(
            value = toggle,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onToggle,
        )
        onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .then(interaction)
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(RowIconSize)
                .background(color = iconColor.copy(alpha = 0.16f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconColor,
            )
        }
        Spacer(Modifier.width(RowIconGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(8.dp))
            trailingContent()
        } else if (selected != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .alpha(if (selected) 1f else 0f),
            )
        }
    }
}

@Composable
fun SettingsTile(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconColor: Color,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    position: ItemPosition = ItemPosition.STANDALONE,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    SettingsCard(
        position = position,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
    ) {
        SettingsCardRow(
            icon = icon,
            iconColor = iconColor,
            title = title,
            subtitle = subtitle,
            titleColor = titleColor,
            enabled = enabled,
            selected = selected,
            trailingContent = trailingContent,
        )
    }
}
