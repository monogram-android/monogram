package org.monogram.presentation.features.chats.conversation.editor.photo.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ColorSelector(
    selectedColor: Color,
    onSelect: (Color) -> Unit
) {
    val colors = listOf(
        Color.White, Color.Black, Color.Gray, Color.Red,
        Color(0xFFFF5722), Color(0xFFFF9800), Color(0xFFFFC107),
        Color.Yellow, Color(0xFF8BC34A), Color.Green,
        Color(0xFF009688), Color.Cyan, Color(0xFF03A9F4),
        Color.Blue, Color(0xFF3F51B5), Color(0xFF673AB7),
        Color.Magenta, Color(0xFFE91E63)
    )

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(colors) { color ->
            val isSelected = color == selectedColor
            Box(
                modifier = Modifier
                    .size(if (isSelected) 36.dp else 32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.2f),
                        shape = CircleShape
                    )
                    .clickable { onSelect(color) }
            )
        }
    }
}
