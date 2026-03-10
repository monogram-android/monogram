package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

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
                Icon(Icons.Rounded.Close, contentDescription = "Clear selection")
            }
        },
        actions = {
            IconButton(onClick = onPinClick) { Icon(Icons.Rounded.PushPin, "Pin") }
            IconButton(onClick = onMuteClick) { Icon(Icons.AutoMirrored.Rounded.VolumeOff, "Mute") }
            IconButton(onClick = onArchiveClick) { Icon(Icons.Rounded.Archive, "Archive") }
            IconButton(onClick = onDeleteClick) { Icon(Icons.Rounded.Delete, "Delete") }
            IconButton(onClick = onMoreClick) { Icon(Icons.Rounded.MoreVert, "More") }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}