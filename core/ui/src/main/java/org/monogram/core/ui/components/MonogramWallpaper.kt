package org.monogram.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Default patterned background shared by chat previews and dialogs. */
@Composable
fun MonogramWallpaper(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val ink = scheme.onSurface.copy(alpha = 0.045f)
    val tile = remember(ink, density.density) { wallpaperTile(ink, density) }
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.surface),
    ) {
        val stepX = tile.width
        val stepY = tile.height
        val columns = (size.width / stepX).toInt() + 1
        val rows = (size.height / stepY).toInt() + 1
        repeat(rows) { row ->
            repeat(columns) { column ->
                drawImage(
                    image = tile,
                    dstOffset = IntOffset(column * stepX, row * stepY),
                    dstSize = IntSize(stepX, stepY),
                    filterQuality = FilterQuality.None,
                )
            }
        }
    }
}

private fun wallpaperTile(ink: Color, density: Density): ImageBitmap {
    val tile = with(density) { 96.dp.toPx() }.roundToInt().coerceAtLeast(1)
    val image = ImageBitmap(tile, tile * 2)
    CanvasDrawScope().draw(
        density = density,
        layoutDirection = LayoutDirection.Ltr,
        canvas = GraphicsCanvas(image),
        size = Size(tile.toFloat(), (tile * 2).toFloat()),
    ) {
        drawPatternRow(ink, y = 0f, x = 0f)
        drawPatternRow(ink, y = tile.toFloat(), x = 0f)
        drawPatternRow(ink, y = tile.toFloat(), x = tile * 0.35f)
        drawPatternRow(ink, y = tile.toFloat(), x = tile * 0.35f - tile)
    }
    return image
}

private fun DrawScope.drawPatternRow(ink: Color, y: Float, x: Float) {
    drawCircle(ink, radius = 9.dp.toPx(), center = Offset(x + 20.dp.toPx(), y + 24.dp.toPx()))
    drawLine(
        color = ink,
        start = Offset(x + 48.dp.toPx(), y + 12.dp.toPx()),
        end = Offset(x + 68.dp.toPx(), y + 32.dp.toPx()),
        strokeWidth = 2.dp.toPx(),
    )
    drawLine(
        color = ink,
        start = Offset(x + 68.dp.toPx(), y + 12.dp.toPx()),
        end = Offset(x + 48.dp.toPx(), y + 32.dp.toPx()),
        strokeWidth = 2.dp.toPx(),
    )
    drawRoundRect(
        color = ink,
        topLeft = Offset(x + 12.dp.toPx(), y + 58.dp.toPx()),
        size = Size(30.dp.toPx(), 16.dp.toPx()),
        cornerRadius = CornerRadius(8.dp.toPx()),
        style = Stroke(width = 2.dp.toPx()),
    )
    drawCircle(ink, radius = 3.dp.toPx(), center = Offset(x + 70.dp.toPx(), y + 70.dp.toPx()))
}
