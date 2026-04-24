package org.monogram.presentation.features.chats.conversation.editor.photo.crop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val GeometryEpsilon = 0.001f

private fun contentToScreen(
    p: Offset, scale: Float, cosR: Float, sinR: Float, offset: Offset, pivot: Offset
): Offset {
    val dx = p.x - pivot.x
    val dy = p.y - pivot.y
    return Offset(
        x = pivot.x + scale * (dx * cosR - dy * sinR) + offset.x,
        y = pivot.y + scale * (dx * sinR + dy * cosR) + offset.y
    )
}

private fun screenToContent(
    screen: Offset, scale: Float, cosR: Float, sinR: Float, offset: Offset, pivot: Offset
): Offset {
    val sx = (screen.x - pivot.x - offset.x) / scale
    val sy = (screen.y - pivot.y - offset.y) / scale
    // R(-rotation) = transpose of R(rotation)
    return Offset(
        x = pivot.x + sx * cosR + sy * sinR,
        y = pivot.y - sx * sinR + sy * cosR
    )
}

private fun projectRectCornersToContentBounds(
    rect: Rect,
    scale: Float,
    rotationDegrees: Float,
    offset: Offset,
    pivot: Offset
): Rect {
    if (rect.isEmpty || scale <= 0f) return Rect.Zero

    val rad = Math.toRadians(rotationDegrees.toDouble())
    val cosR = cos(rad).toFloat()
    val sinR = sin(rad).toFloat()

    val corners = arrayOf(
        rect.topLeft,
        rect.topRight,
        rect.bottomRight,
        rect.bottomLeft
    )

    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY

    for (corner in corners) {
        val contentPoint = screenToContent(corner, scale, cosR, sinR, offset, pivot)
        minX = min(minX, contentPoint.x)
        minY = min(minY, contentPoint.y)
        maxX = max(maxX, contentPoint.x)
        maxY = max(maxY, contentPoint.y)
    }

    return Rect(minX, minY, maxX, maxY)
}

private fun rectCorners(rect: Rect): Array<Offset> = arrayOf(
    rect.topLeft,
    rect.topRight,
    rect.bottomRight,
    rect.bottomLeft
)

private fun Rect.containsWithTolerance(point: Offset, epsilon: Float = GeometryEpsilon): Boolean {
    return point.x >= left - epsilon && point.x <= right + epsilon &&
            point.y >= top - epsilon && point.y <= bottom + epsilon
}

private fun lerpRect(start: Rect, end: Rect, fraction: Float): Rect {
    return Rect(
        left = start.left + (end.left - start.left) * fraction,
        top = start.top + (end.top - start.top) * fraction,
        right = start.right + (end.right - start.right) * fraction,
        bottom = start.bottom + (end.bottom - start.bottom) * fraction
    )
}

fun calculateScalarTransformedBounds(
    baseBounds: Rect,
    scale: Float,
    rotationDegrees: Float,
    offset: Offset,
    pivot: Offset
): Rect {
    val rad = Math.toRadians(rotationDegrees.toDouble())
    val cosR = cos(rad).toFloat()
    val sinR = sin(rad).toFloat()

    val corners = arrayOf(
        baseBounds.topLeft, baseBounds.topRight,
        baseBounds.bottomRight, baseBounds.bottomLeft
    )

    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY

    for (p in corners) {
        val s = contentToScreen(p, scale, cosR, sinR, offset, pivot)
        minX = min(minX, s.x); minY = min(minY, s.y)
        maxX = max(maxX, s.x); maxY = max(maxY, s.y)
    }
    return Rect(minX, minY, maxX, maxY)
}

internal fun isCropRectCoveredByImage(
    baseBounds: Rect,
    cropRect: Rect,
    scale: Float,
    rotationDegrees: Float,
    offset: Offset,
    pivot: Offset
): Boolean {
    if (baseBounds.isEmpty || cropRect.isEmpty || scale <= 0f) return false

    val rad = Math.toRadians(rotationDegrees.toDouble())
    val cosR = cos(rad).toFloat()
    val sinR = sin(rad).toFloat()

    return rectCorners(cropRect).all { corner ->
        baseBounds.containsWithTolerance(
            point = screenToContent(
                screen = corner,
                scale = scale,
                cosR = cosR,
                sinR = sinR,
                offset = offset,
                pivot = pivot
            )
        )
    }
}

internal fun constrainCropRectToImage(
    currentCropRect: Rect,
    candidateRect: Rect,
    visibleBounds: Rect,
    minCropSizePx: Float,
    baseBounds: Rect,
    scale: Float,
    rotationDegrees: Float,
    offset: Offset,
    pivot: Offset
): Rect {
    val constrainedCandidate = constrainCropRect(
        cropRect = candidateRect,
        bounds = visibleBounds,
        minCropSizePx = minCropSizePx
    )

    if (baseBounds.isEmpty || constrainedCandidate.isEmpty || scale <= 0f) return constrainedCandidate
    if (isCropRectCoveredByImage(baseBounds, constrainedCandidate, scale, rotationDegrees, offset, pivot)) {
        return constrainedCandidate
    }
    if (!isCropRectCoveredByImage(baseBounds, currentCropRect, scale, rotationDegrees, offset, pivot)) {
        return currentCropRect
    }

    var low = 0f
    var high = 1f
    repeat(20) {
        val mid = (low + high) / 2f
        val interpolated = lerpRect(currentCropRect, constrainedCandidate, mid)
        if (isCropRectCoveredByImage(baseBounds, interpolated, scale, rotationDegrees, offset, pivot)) {
            low = mid
        } else {
            high = mid
        }
    }

    return lerpRect(currentCropRect, constrainedCandidate, low)
}

fun offsetForZoomAroundAnchor(
    currentOffset: Offset,
    pivot: Offset,
    anchor: Offset,
    zoom: Float
): Offset {
    return Offset(
        x = (1f - zoom) * (anchor.x - pivot.x) + zoom * currentOffset.x,
        y = (1f - zoom) * (anchor.y - pivot.y) + zoom * currentOffset.y
    )
}

fun offsetForRotationAroundAnchor(
    currentOffset: Offset,
    pivot: Offset,
    anchor: Offset,
    deltaAngleDegrees: Float
): Offset {
    val rad = Math.toRadians(deltaAngleDegrees.toDouble())
    val cosD = cos(rad).toFloat()
    val sinD = sin(rad).toFloat()

    val vx = anchor.x - pivot.x - currentOffset.x
    val vy = anchor.y - pivot.y - currentOffset.y
    val rvx = vx * cosD - vy * sinD
    val rvy = vx * sinD + vy * cosD

    return Offset(
        x = anchor.x - pivot.x - rvx,
        y = anchor.y - pivot.y - rvy
    )
}

fun clampOffsetToCoverCrop(
    baseBounds: Rect,
    cropRect: Rect,
    scale: Float,
    rotationDegrees: Float,
    offset: Offset,
    pivot: Offset
): Offset {
    val cropContentBounds = projectRectCornersToContentBounds(
        rect = cropRect,
        scale = scale,
        rotationDegrees = rotationDegrees,
        offset = offset,
        pivot = pivot
    )
    if (cropContentBounds == Rect.Zero) return offset

    val rad = Math.toRadians(rotationDegrees.toDouble())
    val cosR = cos(rad).toFloat()
    val sinR = sin(rad).toFloat()

    var cdx = 0f
    var cdy = 0f

    if (cropContentBounds.left < baseBounds.left) {
        cdx = baseBounds.left - cropContentBounds.left
    } else if (cropContentBounds.right > baseBounds.right) {
        cdx = baseBounds.right - cropContentBounds.right
    }

    if (cropContentBounds.top < baseBounds.top) {
        cdy = baseBounds.top - cropContentBounds.top
    } else if (cropContentBounds.bottom > baseBounds.bottom) {
        cdy = baseBounds.bottom - cropContentBounds.bottom
    }

    if (cdx == 0f && cdy == 0f) return offset

    val screenDx = -scale * (cdx * cosR - cdy * sinR)
    val screenDy = -scale * (cdx * sinR + cdy * cosR)

    return Offset(offset.x + screenDx, offset.y + screenDy)
}

fun fitContentInBounds(
    baseBounds: Rect,
    cropRect: Rect,
    scale: Float,
    rotationDegrees: Float,
    offset: Offset,
    pivot: Offset,
    minScale: Float = 0.5f,
    maxScale: Float = 30f
): Pair<Float, Offset> {
    if (baseBounds.isEmpty || cropRect.isEmpty) return Pair(scale, offset)

    val cropContentBounds = projectRectCornersToContentBounds(
        rect = cropRect,
        scale = scale,
        rotationDegrees = rotationDegrees,
        offset = offset,
        pivot = pivot
    )
    if (cropContentBounds == Rect.Zero) return Pair(scale, offset)

    val cropContentW = cropContentBounds.width
    val cropContentH = cropContentBounds.height
    var newScale = scale

    if (cropContentW > baseBounds.width || cropContentH > baseBounds.height) {
        val scaleX = if (baseBounds.width > 0f) cropContentW / baseBounds.width else 1f
        val scaleY = if (baseBounds.height > 0f) cropContentH / baseBounds.height else 1f
        val correction = max(scaleX, scaleY)
        newScale = (scale * correction).coerceIn(minScale, maxScale)
    }

    val newOffset = if (newScale != scale) {
        val zoomFactor = newScale / scale
        offsetForZoomAroundAnchor(offset, pivot, cropRect.center, zoomFactor)
    } else {
        offset
    }

    val clampedOffset = clampOffsetToCoverCrop(baseBounds, cropRect, newScale, rotationDegrees, newOffset, pivot)
    return Pair(newScale, clampedOffset)
}

fun minimumScaleToCoverCrop(
    baseBounds: Rect,
    cropRect: Rect,
    currentScale: Float,
    rotationDegrees: Float,
    offset: Offset,
    pivot: Offset
): Float {
    if (baseBounds.isEmpty || cropRect.isEmpty || currentScale <= 0f) return currentScale

    val cropContentBounds = projectRectCornersToContentBounds(
        rect = cropRect,
        scale = currentScale,
        rotationDegrees = rotationDegrees,
        offset = offset,
        pivot = pivot
    )
    if (cropContentBounds == Rect.Zero) return currentScale

    val contentSpanX = cropContentBounds.width
    val contentSpanY = cropContentBounds.height

    var minScale = 0f
    if (baseBounds.width > 0f && contentSpanX > 0f) {
        minScale = max(minScale, currentScale * contentSpanX / baseBounds.width)
    }
    if (baseBounds.height > 0f && contentSpanY > 0f) {
        minScale = max(minScale, currentScale * contentSpanY / baseBounds.height)
    }
    return minScale
}
