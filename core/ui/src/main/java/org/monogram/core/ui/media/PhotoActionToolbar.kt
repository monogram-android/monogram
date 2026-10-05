package org.monogram.core.ui.media

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.R

@Composable
internal fun PhotoActionToolbar(
    item: MediaViewerItem,
    actions: MediaViewerActions,
    albumSize: Int,
    session: MediaPlaybackSession?,
    chatKey: String,
    onOpenCaption: () -> Unit,
    onOverflowChange: (Boolean) -> Unit,
    onOpenOverview: () -> Unit = {},
    showOverflow: Boolean = true,
) {
    var groupOverflowOpen by remember { mutableStateOf(false) }
    var actionsOverflowOpen by remember { mutableStateOf(false) }
    LaunchedEffect(groupOverflowOpen, actionsOverflowOpen) {
        onOverflowChange(groupOverflowOpen || actionsOverflowOpen)
    }
    DisposableEffect(Unit) { onDispose { onOverflowChange(false) } }
    val shareLabel = stringResource(R.string.media_action_share)
    val saveLabel = stringResource(R.string.media_action_save)
    val forwardLabel = stringResource(R.string.media_action_forward)
    val copyLabel = stringResource(
        if (item.isVideo) R.string.media_action_copy_video else R.string.media_action_copy_image,
    )
    val overviewLabel = stringResource(R.string.media_mini_player_expand)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ButtonGroup(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                overflowIndicator = { menuState ->
                    LaunchedEffect(menuState.isShowing) { groupOverflowOpen = menuState.isShowing }
                    FilledTonalIconButton(
                        onClick = { menuState.show() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.MoreVert, "$shareLabel, $saveLabel, $forwardLabel")
                    }
                },
            ) {
                if (!item.protectedContent) {
                    mediaAction(shareLabel, Icons.Default.Share, filled = true) { actions.onShare(item) }
                    mediaAction(saveLabel, Icons.Default.Download) { actions.onSave(item) }
                    if (actions.canForward) {
                        mediaAction(forwardLabel, Icons.AutoMirrored.Filled.Reply) {
                            actions.onForward(item, false)
                        }
                    }
                    mediaAction(copyLabel, Icons.Default.ContentCopy) { actions.onCopyMedia(item) }
                }
                if (albumSize > 1) {
                    mediaAction(overviewLabel, Icons.Outlined.Collections, onClick = onOpenOverview)
                }
            }
            if (showOverflow) MediaViewerOverflow(
                item = item,
                actions = actions,
                session = session,
                chatKey = chatKey,
                onOpenCaption = onOpenCaption,
                onVisibilityChange = { actionsOverflowOpen = it },
                albumSize = albumSize,
            )
        }
    }
}

private fun ButtonGroupScope.mediaAction(
    label: String,
    icon: ImageVector,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    customItem(
        buttonGroupContent = {
            val interactionSource = remember { MutableInteractionSource() }
            val modifier = Modifier.size(48.dp).animateWidth(interactionSource, compressionLimit = 8.dp)
            if (filled) {
                FilledIconButton(
                    onClick = onClick,
                    modifier = modifier,
                    interactionSource = interactionSource,
                ) {
                    Icon(icon, label)
                }
            } else {
                FilledTonalIconButton(
                    onClick = onClick,
                    modifier = modifier,
                    interactionSource = interactionSource,
                ) {
                    Icon(icon, label)
                }
            }
        },
        menuContent = { menuState ->
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(icon, contentDescription = null) },
                onClick = {
                    menuState.dismiss()
                    onClick()
                },
            )
        },
    )
}
