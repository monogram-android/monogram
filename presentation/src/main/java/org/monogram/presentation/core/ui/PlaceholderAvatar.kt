package org.monogram.presentation.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

@Composable
fun PlaceholderAvatar(
    name: String,
    fontSize: Int,
    color: Color,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.take(1).uppercase(),
            style = MaterialTheme.typography.titleSmall.copy(fontSize = fontSize.sp),
            color = color
        )
    }
}