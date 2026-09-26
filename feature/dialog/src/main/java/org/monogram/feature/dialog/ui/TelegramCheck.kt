package org.monogram.feature.dialog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Rounded-square check matching rich lists / checklists. */
@Composable
internal fun TelegramRoundCheck(
    checked: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val border = color.copy(alpha = 0.55f)
    Canvas(modifier = modifier.size(size)) {
        val d = this.size.minDimension
        val stroke = (1.75f * density).coerceAtLeast(d * 0.09f)
        val radius = d * 0.22f
        val inset = stroke / 2f
        if (checked) {
            drawRoundRect(
                color = color,
                topLeft = Offset.Zero,
                size = Size(d, d),
                cornerRadius = CornerRadius(radius, radius),
            )
            val start = Offset(d * 0.26f, d * 0.52f)
            val mid = Offset(d * 0.44f, d * 0.70f)
            val end = Offset(d * 0.76f, d * 0.32f)
            val tick = d * 0.12f
            drawLine(Color.White, start, mid, tick, StrokeCap.Round)
            drawLine(Color.White, mid, end, tick, StrokeCap.Round)
        } else {
            drawRoundRect(
                color = border,
                topLeft = Offset(inset, inset),
                size = Size(d - stroke, d - stroke),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = stroke, join = StrokeJoin.Round),
            )
        }
    }
}
