package org.monogram.presentation.features.profile.logs.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DateHeader(date: Int) {
    val dateObj = Date(date.toLong() * 1000)
    val calendar = Calendar.getInstance()
    val currentYear = calendar.get(Calendar.YEAR)
    calendar.time = dateObj
    val eventYear = calendar.get(Calendar.YEAR)

    val pattern = if (currentYear == eventYear) "MMMM dd" else "MMMM dd, yyyy"
    val text = SimpleDateFormat(pattern, Locale.getDefault()).format(dateObj)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.clip(RoundedCornerShape(16.dp))
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun Surface(
    color: androidx.compose.ui.graphics.Color,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.background(color, shape),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
