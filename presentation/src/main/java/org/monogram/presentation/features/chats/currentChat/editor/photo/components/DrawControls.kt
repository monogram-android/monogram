package org.monogram.presentation.features.chats.currentChat.editor.photo.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R

@Composable
fun DrawControls(
    isEraser: Boolean,
    color: Color,
    size: Float,
    onColorChange: (Color) -> Unit,
    onSizeChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (!isEraser) {
            ColorSelector(selectedColor = color, onSelect = onColorChange)
            Spacer(Modifier.height(12.dp))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(if (isEraser) R.string.photo_editor_tool_eraser else R.string.photo_editor_label_size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(48.dp)
            )
            Slider(
                value = size,
                onValueChange = onSizeChange,
                valueRange = 5f..100f,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (isEraser) Color.White else color)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size((size / 100f * 20f).coerceAtLeast(2f).dp)
                        .clip(CircleShape)
                        .background(if (isEraser) Color.Black else Color.White)
                )
            }
        }
    }
}
