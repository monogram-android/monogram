package org.monogram.presentation.features.chats.currentChat.editor.photo.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    onClose: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean
) {
    CenterAlignedTopAppBar(
        title = { },
        navigationIcon = {
            FilledIconButton(
                onClick = onClose,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.3f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Close, stringResource(R.string.photo_editor_action_close))
            }
        },
        actions = {
            if (canUndo || canRedo) {
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        stringResource(R.string.photo_editor_action_undo),
                        tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.3f)
                    )
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Redo,
                        stringResource(R.string.photo_editor_action_redo),
                        tint = if (canRedo) Color.White else Color.White.copy(alpha = 0.3f)
                    )
                }
            }
            Button(
                onClick = onSave,
                modifier = Modifier.padding(horizontal = 8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(stringResource(R.string.photo_editor_action_save))
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            actionIconContentColor = Color.White,
            navigationIconContentColor = Color.White
        )
    )
}
