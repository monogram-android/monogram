package org.monogram.presentation.features.chats.currentChat.editor.photo.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R

@Composable
fun TransformControls(
    rotation: Float,
    scale: Float,
    onRotationChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilledTonalIconButton(onClick = { onRotationChange(rotation - 90f) }) {
                Icon(
                    Icons.Rounded.RotateLeft,
                    contentDescription = stringResource(R.string.photo_editor_action_rotate_left)
                )
            }

            OutlinedButton(
                onClick = onReset,
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.photo_editor_action_reset))
            }

            FilledTonalIconButton(onClick = { onRotationChange(rotation + 90f) }) {
                Icon(
                    Icons.Rounded.RotateRight,
                    contentDescription = stringResource(R.string.photo_editor_action_rotate_right)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.photo_editor_label_zoom),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(48.dp)
            )
            Slider(
                value = scale,
                onValueChange = onScaleChange,
                valueRange = 0.5f..3f,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
