package org.monogram.presentation.settings.proxy

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProxyItem(
    proxy: ProxyModel,
    errorMessage: String?,
    isChecking: Boolean,
    isFavorite: Boolean,
    position: ItemPosition,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRefreshPing: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val typeName = when (proxy.type) {
        is ProxyTypeModel.Mtproto -> "MTProto"
        is ProxyTypeModel.Socks5 -> "SOCKS5"
        is ProxyTypeModel.Http -> "HTTP"
    }

    val isEnabled = proxy.isEnabled
    val ping = proxy.ping
    val statusText = when {
        isChecking -> stringResource(R.string.proxy_checking)
        !errorMessage.isNullOrBlank() -> stringResource(R.string.proxy_offline)
        ping != null && ping >= 0L -> stringResource(R.string.proxy_ping_format, ping.toInt())
        isEnabled -> stringResource(R.string.proxy_enabled)
        else -> typeName
    }

    val cornerRadius = 24.dp
    val shape = when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )

        ItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius,
            topStart = 4.dp,
            topEnd = 4.dp
        )

        ItemPosition.STANDALONE -> RoundedCornerShape(cornerRadius)
    }

    val backgroundColor by animateColorAsState(
        if (isEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surfaceContainer,
        label = "bg"
    )

    Surface(
        color = backgroundColor,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isEnabled) Icons.Rounded.Check else Icons.Rounded.Language,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (isEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = proxy.server,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isFavorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = stringResource(R.string.proxy_action_remove_favorite),
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = typeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Port ${proxy.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    ProxyStatusPill(
                        text = statusText,
                        backgroundColor = when {
                            isChecking -> MaterialTheme.colorScheme.surfaceVariant
                            !errorMessage.isNullOrBlank() -> MaterialTheme.colorScheme.errorContainer
                            isEnabled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = when {
                            !errorMessage.isNullOrBlank() -> MaterialTheme.colorScheme.onErrorContainer
                            isEnabled -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
                if (!errorMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                ProxyPingIndicator(
                    ping = proxy.ping,
                    isChecking = isChecking,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefreshPing, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.refresh_list_title),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenMenu, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.more_options_cd),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeToDeleteContainer(
    onDelete: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val color by animateColorAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    Color.Transparent
                },
                label = "color"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        },
        content = { content() }
    )
}

@Composable
internal fun SectionHeader(
    text: String,
    subtitle: String? = null,
    onSubtitleClick: (() -> Unit)? = null
) {
    Column(modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (onSubtitleClick != null) {
                    Modifier.clickable(onClick = onSubtitleClick)
                } else {
                    Modifier
                }
            )
        }
    }
}
