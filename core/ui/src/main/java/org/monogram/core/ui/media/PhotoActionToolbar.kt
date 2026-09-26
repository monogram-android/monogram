package org.monogram.core.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.R
import org.monogram.core.ui.menu.AppMenuGroup
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface

/** Share / save / forward plus the overflow, as one floating pill. */
@Composable
internal fun PhotoActionToolbar(
    item: MediaViewerItem,
    actions: MediaViewerActions,
    albumSize: Int,
    session: MediaPlaybackSession?,
    chatKey: String,
    onOpenCaption: () -> Unit,
    onOverflowChange: (Boolean) -> Unit,
) {
    var forwardMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { actions.onShare(item) },
                    enabled = !item.protectedContent,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Share, stringResource(R.string.media_action_share))
                }
                IconButton(
                    onClick = { actions.onSave(item) },
                    enabled = !item.protectedContent,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Download, stringResource(R.string.media_action_save))
                }
                if (actions.canForward && !item.protectedContent) Box {
                    IconButton(
                        onClick = { if (albumSize > 1) forwardMenu = true else actions.onForward(item, false) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Reply, stringResource(R.string.media_action_forward))
                    }
                    AppMenuPopup(expanded = forwardMenu, onDismiss = { forwardMenu = false }) {
                        AppMenuSurface {
                            AppMenuGroup {
                                AppMenuItem(
                                    text = stringResource(R.string.media_forward_this),
                                    icon = Icons.AutoMirrored.Outlined.Forward,
                                    onClick = { forwardMenu = false; actions.onForward(item, false) },
                                )
                                AppMenuItem(
                                    text = stringResource(R.string.media_forward_album, albumSize),
                                    icon = Icons.Outlined.Collections,
                                    onClick = { forwardMenu = false; actions.onForward(item, true) },
                                )
                            }
                        }
                    }
                }
                MediaViewerOverflow(
                    item = item,
                    actions = actions,
                    session = session,
                    chatKey = chatKey,
                    onOpenCaption = onOpenCaption,
                    onVisibilityChange = onOverflowChange,
                )
            }
        }
    }
}
