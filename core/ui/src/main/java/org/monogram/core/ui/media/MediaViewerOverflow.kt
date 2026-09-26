package org.monogram.core.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.R
import org.monogram.core.ui.menu.AppMenuGroup
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface

/** Overflow menu actions for viewer items. */
@Composable
internal fun MediaViewerOverflow(
    item: MediaViewerItem,
    actions: MediaViewerActions,
    session: MediaPlaybackSession?,
    chatKey: String,
    onOpenCaption: () -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var loopEnabled by remember(item.id) { mutableStateOf(item.loops) }
    LaunchedEffect(menuOpen, speedMenu) { onVisibilityChange(menuOpen || speedMenu) }
    DisposableEffect(Unit) { onDispose { onVisibilityChange(false) } }

    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.media_more_actions))
        }
        AppMenuPopup(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            alignToAnchorEnd = true,
        ) {
            val seenBy = actions.seenByLabel?.invoke(item)
            val openSeenBy = actions.onOpenSeenBy
            if (!seenBy.isNullOrBlank() && openSeenBy != null) {
                AppMenuSurface {
                    AppMenuGroup {
                        AppMenuItem(
                            text = seenBy,
                            icon = Icons.Outlined.Visibility,
                            onClick = {
                                menuOpen = false
                                openSeenBy(item)
                            },
                        )
                    }
                }
            }
            if (item.isVideo && session != null) {
                AppMenuSurface {
                    AppMenuGroup {
                        AppMenuItem(
                            text = stringResource(R.string.media_menu_speed, formatSpeed(session.speed)),
                            icon = Icons.Default.Speed,
                            onClick = { speedMenu = true },
                        )
                        AppMenuItem(
                            text = stringResource(R.string.media_menu_loop),
                            icon = Icons.Default.Loop,
                            trailing = { Switch(checked = loopEnabled, onCheckedChange = null) },
                            onClick = {
                                loopEnabled = !loopEnabled
                                session.setLoopingCurrent(loopEnabled)
                            },
                        )
                        if (actions.canPictureInPicture) {
                            AppMenuItem(
                                text = stringResource(R.string.media_menu_pip),
                                icon = Icons.Default.PictureInPictureAlt,
                                onClick = {
                                    menuOpen = false
                                    actions.onEnterPictureInPicture()
                                },
                            )
                        }
                        AppMenuItem(
                            text = stringResource(R.string.media_menu_listen_background),
                            icon = Icons.Default.Headphones,
                            onClick = {
                                menuOpen = false
                                actions.onListenInBackground()
                            },
                        )
                    }
                }
            }
            AppMenuSurface {
                AppMenuGroup {
                    if (item.caption != null) {
                        AppMenuItem(
                            text = stringResource(R.string.media_action_caption_sheet),
                            icon = Icons.AutoMirrored.Outlined.Notes,
                            onClick = {
                                menuOpen = false
                                onOpenCaption()
                            },
                        )
                    }
                    AppMenuItem(
                        text = stringResource(R.string.media_action_show_in_chat),
                        icon = Icons.Outlined.ChatBubbleOutline,
                        onClick = {
                            menuOpen = false
                            actions.onShowInChat(item)
                        },
                    )
                }
                AppMenuGroup {
                    if (item.caption != null) {
                        AppMenuItem(
                            text = stringResource(R.string.media_action_copy_caption),
                            icon = Icons.Default.ContentCopy,
                            onClick = {
                                menuOpen = false
                                actions.onCopyCaption(item)
                            },
                        )
                    }
                    if (!item.protectedContent) {
                        AppMenuItem(
                            text = stringResource(
                                if (item.isVideo) R.string.media_action_copy_video else R.string.media_action_copy_image,
                            ),
                            icon = Icons.Default.Image,
                            onClick = {
                                menuOpen = false
                                actions.onCopyMedia(item)
                            },
                        )
                    }
                    AppMenuItem(
                        text = stringResource(R.string.media_action_open_externally),
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        onClick = {
                            menuOpen = false
                            actions.onOpenExternally(item)
                        },
                    )
                }
                if (actions.canDelete && !item.protectedContent) {
                    AppMenuGroup {
                        AppMenuItem(
                            text = stringResource(R.string.media_action_delete),
                            icon = Icons.Default.Delete,
                            destructive = true,
                            onClick = {
                                menuOpen = false
                                actions.onDelete(item, false)
                            },
                        )
                    }
                }
            }
        }
        AppMenuPopup(
            expanded = speedMenu,
            onDismiss = { speedMenu = false },
            alignToAnchorEnd = true,
        ) {
            AppMenuSurface {
                AppMenuGroup {
                    SPEEDS.forEach { speed ->
                        AppMenuItem(
                            text = formatSpeed(speed),
                            onClick = {
                                speedMenu = false
                                menuOpen = false
                                session?.changeSpeed(speed)
                                MediaViewerPrefs.setSpeed(context, chatKey, speed)
                            },
                        )
                    }
                }
            }
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f)
private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"
