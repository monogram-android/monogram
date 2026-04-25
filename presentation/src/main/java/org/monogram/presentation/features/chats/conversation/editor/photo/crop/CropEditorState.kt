package org.monogram.presentation.features.chats.conversation.editor.photo.crop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Stable
class CropEditorState internal constructor(
    val minCropSizePx: Float,
    val imageBounds: Rect,
    val currentImageBounds: Rect,
    val defaultCropRect: Rect,
    val cropRect: Rect,
    val updateCropRect: (Rect) -> Unit,
    val setCropRect: (Rect) -> Unit,
    val reset: () -> Unit
)

fun calculateTargetFillRect(canvasSize: IntSize, aspectRatio: Float): Rect {
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || aspectRatio <= 0f) return Rect.Zero
    val cw = canvasSize.width.toFloat()
    val ch = canvasSize.height.toFloat()
    val centerX = cw / 2f
    val centerY = ch / 2f

    val w: Float
    val h: Float
    if (ch * aspectRatio > cw) {
        w = cw
        h = cw / aspectRatio
    } else {
        h = ch
        w = ch * aspectRatio
    }
    return Rect(centerX - w / 2f, centerY - h / 2f, centerX + w / 2f, centerY + h / 2f)
}

@Composable
fun rememberCropEditorState(
    canvasSize: IntSize,
    imageSize: IntSize,
    transformPivot: Offset,
    imageScale: Float,
    imageRotation: Float,
    imageOffset: Offset
): CropEditorState {
    val density = LocalDensity.current
    val minCropSizePx = remember(density) { with(density) { 96.dp.toPx() } }

    val imageBounds by remember(canvasSize, imageSize) {
        derivedStateOf { calculateCropRect(canvasSize, imageSize) }
    }
    val defaultCropRect by remember(imageBounds) {
        derivedStateOf { imageBounds }
    }

    var cropRect by remember { mutableStateOf(Rect.Zero) }
    var previousImageBounds by remember { mutableStateOf(Rect.Zero) }

    val currentImageBounds by remember(imageBounds, imageScale, imageRotation, imageOffset, transformPivot) {
        derivedStateOf {
            if (imageBounds != Rect.Zero) {
                calculateScalarTransformedBounds(
                    baseBounds = imageBounds,
                    scale = imageScale,
                    rotationDegrees = imageRotation,
                    offset = imageOffset,
                    pivot = transformPivot
                )
            } else {
                imageBounds
            }
        }
    }

    LaunchedEffect(imageBounds) {
        if (imageBounds == Rect.Zero) {
            cropRect = Rect.Zero
            previousImageBounds = Rect.Zero
        } else if (cropRect == Rect.Zero || previousImageBounds == Rect.Zero) {
            cropRect = imageBounds
            previousImageBounds = imageBounds
        } else if (previousImageBounds != imageBounds) {
            cropRect = constrainCropRect(
                cropRect = remapRectToBounds(cropRect, previousImageBounds, imageBounds),
                bounds = imageBounds,
                minCropSizePx = minCropSizePx
            )
            previousImageBounds = imageBounds
        }
    }

    return CropEditorState(
        minCropSizePx = minCropSizePx,
        imageBounds = imageBounds,
        currentImageBounds = currentImageBounds,
        defaultCropRect = defaultCropRect,
        cropRect = cropRect,
        updateCropRect = { candidate ->
            cropRect = constrainCropRectToImage(
                currentCropRect = cropRect,
                candidateRect = candidate,
                visibleBounds = currentImageBounds,
                minCropSizePx = minCropSizePx,
                baseBounds = imageBounds,
                scale = imageScale,
                rotationDegrees = imageRotation,
                offset = imageOffset,
                pivot = transformPivot
            )
        },
        setCropRect = { rect ->
            cropRect = rect
        },
        reset = {
            cropRect = defaultCropRect
        }
    )
}

private fun remapRectToBounds(rect: Rect, fromBounds: Rect, toBounds: Rect): Rect {
    if (rect == Rect.Zero || fromBounds.width <= 0f || fromBounds.height <= 0f) return toBounds

    val leftFraction = (rect.left - fromBounds.left) / fromBounds.width
    val topFraction = (rect.top - fromBounds.top) / fromBounds.height
    val rightFraction = (rect.right - fromBounds.left) / fromBounds.width
    val bottomFraction = (rect.bottom - fromBounds.top) / fromBounds.height

    return Rect(
        left = toBounds.left + toBounds.width * leftFraction,
        top = toBounds.top + toBounds.height * topFraction,
        right = toBounds.left + toBounds.width * rightFraction,
        bottom = toBounds.top + toBounds.height * bottomFraction
    )
}
