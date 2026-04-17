package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ExpressiveDefaults

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    isInArchive: Boolean,
    allPinned: Boolean,
    allMuted: Boolean,
    canPin: Boolean,
    canMute: Boolean,
    canArchive: Boolean,
    canDelete: Boolean,
    canToggleRead: Boolean,
    onClearSelection: () -> Unit,
    onPinClick: () -> Unit,
    onMuteClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onToggleReadClick: () -> Unit,
    canMarkUnread: Boolean
) {
    val iconButtonShapes = ExpressiveDefaults.iconButtonShapes()

    TopAppBar(
        title = {
            Text(
                text = "$selectedCount",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        navigationIcon = {
            IconButton(onClick = onClearSelection, shapes = iconButtonShapes) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_clear_selection))
            }
        },
        actions = {
            if (canPin) {
                IconButton(onClick = onPinClick, shapes = iconButtonShapes) {
                    Icon(
                        Icons.Rounded.PushPin,
                        stringResource(if (allPinned) R.string.action_unpin else R.string.action_pin)
                    )
                }
            }
            if (canMute) {
                IconButton(onClick = onMuteClick, shapes = iconButtonShapes) {
                    Icon(
                        imageVector = if (allMuted) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                        contentDescription = stringResource(if (allMuted) R.string.menu_unmute else R.string.menu_mute)
                    )
                }
            }
            if (canArchive) {
                IconButton(onClick = onArchiveClick, shapes = iconButtonShapes) {
                    Icon(
                        imageVector = if (isInArchive) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                        contentDescription = stringResource(if (isInArchive) R.string.menu_unarchive else R.string.menu_archive)
                    )
                }
            }
            if (canDelete) {
                IconButton(onClick = onDeleteClick, shapes = iconButtonShapes) {
                    Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete))
                }
            }
            if (canToggleRead) {
                IconButton(onClick = onToggleReadClick, shapes = iconButtonShapes) {
                    Icon(
                        Icons.Rounded.DoneAll,
                        stringResource(if (canMarkUnread) R.string.action_mark_as_unread else R.string.action_mark_as_read)
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}