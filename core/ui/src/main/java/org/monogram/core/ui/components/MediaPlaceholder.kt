package org.monogram.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MediaPlaceholder(
    failed: Boolean,
    modifier: Modifier = Modifier,
    failedText: String,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .then(if (failed) Modifier else Modifier.monoPlaceholder(RoundedCornerShape(20.dp))),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Text(
                text = failedText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
