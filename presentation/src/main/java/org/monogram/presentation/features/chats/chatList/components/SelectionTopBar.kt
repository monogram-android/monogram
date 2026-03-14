package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import org.monogram.presentation.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onPinClick: () -> Unit,
    onMuteClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = "$selectedCount",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_clear_selection))
            }
        },
        actions = {
            IconButton(onClick = onPinClick) { Icon(Icons.Rounded.PushPin, stringResource(R.string.action_pin)) }
            IconButton(onClick = onMuteClick) { Icon(Icons.AutoMirrored.Rounded.VolumeOff, stringResource(R.string.menu_mute)) }
            IconButton(onClick = onArchiveClick) { Icon(Icons.Rounded.Archive, stringResource(R.string.menu_archive)) }
            IconButton(onClick = onDeleteClick) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) }
            IconButton(onClick = onMoreClick) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options_cd)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}