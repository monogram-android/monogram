package org.monogram.presentation.features.chats.currentChat.editor.photo.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun CropOverlay(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    ) {
        val width = size.width
        val height = size.height

        val padding = 32.dp.toPx()
        val cropWidth = width - padding * 2
        val cropHeight = height - padding * 2

        val rect = Rect(
            offset = Offset(padding, padding),
            size = Size(cropWidth, cropHeight)
        )

        drawRect(
            color = Color.Black.copy(alpha = 0.7f),
            size = size
        )

        drawRect(
            color = Color.Transparent,
            topLeft = rect.topLeft,
            size = rect.size,
            blendMode = BlendMode.Clear
        )

        val strokeWidth = 1.dp.toPx()
        val gridColor = Color.White.copy(alpha = 0.5f)

        drawLine(
            color = gridColor,
            start = Offset(rect.left + rect.width / 3, rect.top),
            end = Offset(rect.left + rect.width / 3, rect.bottom),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = gridColor,
            start = Offset(rect.left + rect.width * 2 / 3, rect.top),
            end = Offset(rect.left + rect.width * 2 / 3, rect.bottom),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = gridColor,
            start = Offset(rect.left, rect.top + rect.height / 3),
            end = Offset(rect.right, rect.top + rect.height / 3),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = gridColor,
            start = Offset(rect.left, rect.top + rect.height * 2 / 3),
            end = Offset(rect.right, rect.top + rect.height * 2 / 3),
            strokeWidth = strokeWidth
        )

        val cornerLen = 24.dp.toPx()
        val cornerStroke = 3.dp.toPx()
        val cornerColor = Color.White

        drawLine(cornerColor, rect.topLeft, rect.topLeft.copy(x = rect.left + cornerLen), cornerStroke)
        drawLine(cornerColor, rect.topLeft, rect.topLeft.copy(y = rect.top + cornerLen), cornerStroke)

        drawLine(cornerColor, rect.topRight, rect.topRight.copy(x = rect.right - cornerLen), cornerStroke)
        drawLine(cornerColor, rect.topRight, rect.topRight.copy(y = rect.top + cornerLen), cornerStroke)

        drawLine(cornerColor, rect.bottomLeft, rect.bottomLeft.copy(x = rect.left + cornerLen), cornerStroke)
        drawLine(cornerColor, rect.bottomLeft, rect.bottomLeft.copy(y = rect.bottom - cornerLen), cornerStroke)

        drawLine(cornerColor, rect.bottomRight, rect.bottomRight.copy(x = rect.right - cornerLen), cornerStroke)
        drawLine(cornerColor, rect.bottomRight, rect.bottomRight.copy(y = rect.bottom - cornerLen), cornerStroke)

        drawRect(
            color = Color.White.copy(alpha = 0.8f),
            topLeft = rect.topLeft,
            size = rect.size,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}