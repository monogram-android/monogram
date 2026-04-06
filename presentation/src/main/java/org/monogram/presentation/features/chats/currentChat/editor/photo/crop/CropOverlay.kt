package org.monogram.presentation.features.chats.currentChat.editor.photo.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

fun calculateCropRect(bounds: IntSize, imageSize: IntSize): Rect {
    if (bounds.width <= 0 || bounds.height <= 0 || imageSize.width <= 0 || imageSize.height <= 0) {
        return Rect.Zero
    }
    val imageAspect = imageSize.width.toFloat() / imageSize.height.toFloat()
    val canvasAspect = bounds.width.toFloat() / bounds.height.toFloat()
    return if (imageAspect > canvasAspect) {
        val fittedHeight = bounds.width / imageAspect
        val top = (bounds.height - fittedHeight) / 2f
        Rect(0f, top, bounds.width.toFloat(), top + fittedHeight)
    } else {
        val fittedWidth = bounds.height * imageAspect
        val left = (bounds.width - fittedWidth) / 2f
        Rect(left, 0f, left + fittedWidth, bounds.height.toFloat())
    }
}

fun constrainCropRect(cropRect: Rect, bounds: Rect, minCropSizePx: Float): Rect {
    val b = Rect(
        left = minOf(bounds.left, bounds.right),
        top = minOf(bounds.top, bounds.bottom),
        right = maxOf(bounds.left, bounds.right),
        bottom = maxOf(bounds.top, bounds.bottom)
    )
    if (b.width <= 0f || b.height <= 0f) return cropRect
    val minW = minCropSizePx.coerceAtMost(b.width)
    val minH = minCropSizePx.coerceAtMost(b.height)
    val w = cropRect.width.coerceIn(minW, b.width)
    val h = cropRect.height.coerceIn(minH, b.height)
    val l = cropRect.left.coerceIn(b.left, (b.right - w).coerceAtLeast(b.left))
    val t = cropRect.top.coerceIn(b.top, (b.bottom - h).coerceAtLeast(b.top))
    return Rect(l, t, l + w, t + h)
}



private enum class CropHandle {
    NONE, MOVE,
    TOP_LEFT, TOP, TOP_RIGHT, RIGHT,
    BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT
}



@Composable
fun CropScrim(cropRect: Rect, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    ) {
        drawRect(Color.Black.copy(alpha = 0.7f), size = size)
        drawRect(Color.Transparent, topLeft = cropRect.topLeft, size = cropRect.size, blendMode = BlendMode.Clear)
    }
}



@Composable
fun CropOverlay(
    cropRect: Rect,
    bounds: Rect,
    minCropSizePx: Float,
    onCropRectChange: (Rect) -> Unit,
    onContentTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit = { _, _, _ -> },
    onResizeEnded: () -> Unit = {},
    onDragStateChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentCropRect by rememberUpdatedState(cropRect)
    val currentOnCropRectChange by rememberUpdatedState(onCropRectChange)
    val currentOnContentTransform by rememberUpdatedState(onContentTransform)
    val currentOnResizeEnded by rememberUpdatedState(onResizeEnded)
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)
    val handleTouchRadiusPx = 28.dp
    val cornerHandleZonePx = 44.dp
    val sideHandleLengthPx = 36.dp
    val sideTouchInsetPx = 24.dp

    
    var isResizing by remember { mutableStateOf(false) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            
            .pointerInput(bounds, minCropSizePx) {
                val handleTouchRadius = handleTouchRadiusPx.toPx()
                val cornerHandleZone = cornerHandleZonePx.toPx()
                val sideTouchInset = sideTouchInsetPx.toPx()

                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val activeHandle = pickCropHandle(
                        down.position, currentCropRect,
                        handleTouchRadius, cornerHandleZone, sideTouchInset
                    )
                    
                    if (activeHandle == CropHandle.NONE || activeHandle == CropHandle.MOVE) {
                        return@awaitEachGesture
                    }

                    var dragRect = currentCropRect
                    down.consume()
                    isResizing = true
                    currentOnDragStateChange(true)

                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val primary = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull { it.pressed }
                            if (primary == null || !primary.pressed) break

                            val drag = primary.position - primary.previousPosition
                            if (drag == Offset.Zero) continue
                            primary.consume()

                            dragRect = resizeCropRect(dragRect, activeHandle, drag, bounds, minCropSizePx)
                            currentOnCropRectChange(dragRect)
                        }
                    } finally {
                        isResizing = false
                        currentOnResizeEnded()
                        currentOnDragStateChange(false)
                    }
                }
            }
            
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (!isResizing) {
                        currentOnContentTransform(centroid, pan, zoom)
                    }
                }
            }
    ) {
        
        val strokeWidth = 1.dp.toPx()
        val gridColor = Color.White.copy(alpha = 0.5f)
        drawLine(gridColor, Offset(cropRect.left + cropRect.width / 3, cropRect.top), Offset(cropRect.left + cropRect.width / 3, cropRect.bottom), strokeWidth)
        drawLine(gridColor, Offset(cropRect.left + cropRect.width * 2 / 3, cropRect.top), Offset(cropRect.left + cropRect.width * 2 / 3, cropRect.bottom), strokeWidth)
        drawLine(gridColor, Offset(cropRect.left, cropRect.top + cropRect.height / 3), Offset(cropRect.right, cropRect.top + cropRect.height / 3), strokeWidth)
        drawLine(gridColor, Offset(cropRect.left, cropRect.top + cropRect.height * 2 / 3), Offset(cropRect.right, cropRect.top + cropRect.height * 2 / 3), strokeWidth)

        
        val cornerLen = 24.dp.toPx()
        val cornerStroke = 3.dp.toPx()
        val white = Color.White
        drawLine(white, cropRect.topLeft, cropRect.topLeft.copy(x = cropRect.left + cornerLen), cornerStroke)
        drawLine(white, cropRect.topLeft, cropRect.topLeft.copy(y = cropRect.top + cornerLen), cornerStroke)
        drawLine(white, cropRect.topRight, cropRect.topRight.copy(x = cropRect.right - cornerLen), cornerStroke)
        drawLine(white, cropRect.topRight, cropRect.topRight.copy(y = cropRect.top + cornerLen), cornerStroke)
        drawLine(white, cropRect.bottomLeft, cropRect.bottomLeft.copy(x = cropRect.left + cornerLen), cornerStroke)
        drawLine(white, cropRect.bottomLeft, cropRect.bottomLeft.copy(y = cropRect.bottom - cornerLen), cornerStroke)
        drawLine(white, cropRect.bottomRight, cropRect.bottomRight.copy(x = cropRect.right - cornerLen), cornerStroke)
        drawLine(white, cropRect.bottomRight, cropRect.bottomRight.copy(y = cropRect.bottom - cornerLen), cornerStroke)

        
        val sideLen = sideHandleLengthPx.toPx()
        val sideStroke = 4.dp.toPx()
        val cx = cropRect.left + cropRect.width / 2f
        val cy = cropRect.top + cropRect.height / 2f
        val half = sideLen / 2f
        drawLine(white, Offset(cx - half, cropRect.top), Offset(cx + half, cropRect.top), sideStroke)
        drawLine(white, Offset(cx - half, cropRect.bottom), Offset(cx + half, cropRect.bottom), sideStroke)
        drawLine(white, Offset(cropRect.left, cy - half), Offset(cropRect.left, cy + half), sideStroke)
        drawLine(white, Offset(cropRect.right, cy - half), Offset(cropRect.right, cy + half), sideStroke)

        
        drawRect(Color.White.copy(alpha = 0.8f), cropRect.topLeft, cropRect.size, style = Stroke(1.dp.toPx()))
    }
}



private fun pickCropHandle(
    point: Offset, crop: Rect,
    touchRadius: Float, cornerZone: Float, sideInset: Float
): CropHandle {
    val topBand = (crop.top - sideInset)..(crop.top + sideInset)
    val bottomBand = (crop.bottom - sideInset)..(crop.bottom + sideInset)
    val leftBand = (crop.left - sideInset)..(crop.left + sideInset)
    val rightBand = (crop.right - sideInset)..(crop.right + sideInset)
    val inH = point.x in crop.left..crop.right
    val inV = point.y in crop.top..crop.bottom
    return when {
        inCorner(point, crop, CropHandle.TOP_LEFT, touchRadius, cornerZone) -> CropHandle.TOP_LEFT
        inCorner(point, crop, CropHandle.TOP_RIGHT, touchRadius, cornerZone) -> CropHandle.TOP_RIGHT
        inCorner(point, crop, CropHandle.BOTTOM_RIGHT, touchRadius, cornerZone) -> CropHandle.BOTTOM_RIGHT
        inCorner(point, crop, CropHandle.BOTTOM_LEFT, touchRadius, cornerZone) -> CropHandle.BOTTOM_LEFT
        inH && point.y in topBand -> CropHandle.TOP
        inV && point.x in rightBand -> CropHandle.RIGHT
        inH && point.y in bottomBand -> CropHandle.BOTTOM
        inV && point.x in leftBand -> CropHandle.LEFT
        inH && inV -> CropHandle.MOVE
        else -> CropHandle.NONE
    }
}

private fun resizeCropRect(crop: Rect, handle: CropHandle, drag: Offset, bounds: Rect, minSize: Float): Rect {
    if (bounds.width <= 0f || bounds.height <= 0f) return crop
    val minW = minSize.coerceAtMost(bounds.width)
    val minH = minSize.coerceAtMost(bounds.height)
    var l = crop.left; var t = crop.top; var r = crop.right; var b = crop.bottom
    when (handle) {
        CropHandle.MOVE -> { /* not used here */ }
        CropHandle.TOP_LEFT -> { l = (l + drag.x).coerceIn(bounds.left, r - minW); t = (t + drag.y).coerceIn(bounds.top, b - minH) }
        CropHandle.TOP -> { t = (t + drag.y).coerceIn(bounds.top, b - minH) }
        CropHandle.TOP_RIGHT -> { r = (r + drag.x).coerceIn(l + minW, bounds.right); t = (t + drag.y).coerceIn(bounds.top, b - minH) }
        CropHandle.RIGHT -> { r = (r + drag.x).coerceIn(l + minW, bounds.right) }
        CropHandle.BOTTOM_RIGHT -> { r = (r + drag.x).coerceIn(l + minW, bounds.right); b = (b + drag.y).coerceIn(t + minH, bounds.bottom) }
        CropHandle.BOTTOM -> { b = (b + drag.y).coerceIn(t + minH, bounds.bottom) }
        CropHandle.BOTTOM_LEFT -> { l = (l + drag.x).coerceIn(bounds.left, r - minW); b = (b + drag.y).coerceIn(t + minH, bounds.bottom) }
        CropHandle.LEFT -> { l = (l + drag.x).coerceIn(bounds.left, r - minW) }
        CropHandle.NONE -> {}
    }
    return Rect(l, t, r, b)
}

private fun inCorner(point: Offset, crop: Rect, handle: CropHandle, radius: Float, zone: Float): Boolean {
    val r = when (handle) {
        CropHandle.TOP_LEFT -> Rect(crop.left - radius, crop.top - radius, crop.left + zone, crop.top + zone)
        CropHandle.TOP_RIGHT -> Rect(crop.right - zone, crop.top - radius, crop.right + radius, crop.top + zone)
        CropHandle.BOTTOM_RIGHT -> Rect(crop.right - zone, crop.bottom - zone, crop.right + radius, crop.bottom + radius)
        CropHandle.BOTTOM_LEFT -> Rect(crop.left - radius, crop.bottom - zone, crop.left + zone, crop.bottom + radius)
        else -> return false
    }
    return point.x in r.left..r.right && point.y in r.top..r.bottom
}
