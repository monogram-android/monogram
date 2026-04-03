package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.*
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
            IconButton(onClick = onPinClick, shapes = iconButtonShapes) {
                Icon(
                    Icons.Rounded.PushPin,
                    stringResource(if (allPinned) R.string.action_unpin else R.string.action_pin)
                )
            }
            IconButton(onClick = onMuteClick, shapes = iconButtonShapes) {
                Icon(
                    imageVector = if (allMuted) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                    contentDescription = stringResource(if (allMuted) R.string.menu_unmute else R.string.menu_mute)
                )
            }
            IconButton(onClick = onArchiveClick, shapes = iconButtonShapes) {
                Icon(
                    imageVector = if (isInArchive) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                    contentDescription = stringResource(if (isInArchive) R.string.menu_unarchive else R.string.menu_archive)
                )
            }
            IconButton(onClick = onDeleteClick, shapes = iconButtonShapes) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete))
            }
            IconButton(onClick = onToggleReadClick, shapes = iconButtonShapes) {
                Icon(
                    Icons.Rounded.DoneAll,
                    stringResource(if (canMarkUnread) R.string.action_mark_as_unread else R.string.action_mark_as_read)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}