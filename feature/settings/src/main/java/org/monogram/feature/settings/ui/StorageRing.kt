package org.monogram.feature.settings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
internal fun StorageRing(
    slices: List<Pair<Color, Long>>,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.second }.coerceAtLeast(1L)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier = modifier.size(180.dp)) {
        val strokeWidth = 18.dp.toPx()
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth),
        )
        val live = slices.filter { it.second > 0L }
        val gap = if (live.size > 1) 4f else 0f
        val stroke = Stroke(width = strokeWidth)
        var start = -90f
        live.forEach { (color, bytes) ->
            val sweep = 360f * (bytes.toFloat() / total.toFloat())
            val sliceGap = minOf(gap, sweep / 2f)
            drawArc(
                color = color,
                startAngle = start + sliceGap / 2f,
                sweepAngle = sweep - sliceGap,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            start += sweep
        }
    }
}
