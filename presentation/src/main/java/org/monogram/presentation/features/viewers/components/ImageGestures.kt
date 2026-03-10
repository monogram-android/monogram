package org.monogram.presentation.features.viewers.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.sign

class ZoomState {
    val scale = Animatable(1f)
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)

    fun resetInstant(scope: CoroutineScope) {
        scope.launch {
            scale.snapTo(1f)
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
        }
    }

    fun onDoubleTap(scope: CoroutineScope, tap: Offset, targetScale: Float, size: IntSize) {
        scope.launch {
            if (targetScale == 1f) {
                launch { scale.animateTo(1f, spring()) }
                launch { offsetX.animateTo(0f, spring()) }
                launch { offsetY.animateTo(0f, spring()) }
                return@launch
            }

            val currentScale = scale.value
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val zoomFactor = targetScale / currentScale
            val tapXFromCenter = tap.x - centerX
            val tapYFromCenter = tap.y - centerY

            val targetOffsetX = (tapXFromCenter * (1 - zoomFactor)) + (offsetX.value * zoomFactor)
            val targetOffsetY = (tapYFromCenter * (1 - zoomFactor)) + (offsetY.value * zoomFactor)

            val maxOffsetX = (size.width * (targetScale - 1f)) / 2f
            val maxOffsetY = (size.height * (targetScale - 1f)) / 2f

            val clampedX = targetOffsetX.coerceIn(-maxOffsetX, maxOffsetX)
            val clampedY = targetOffsetY.coerceIn(-maxOffsetY, maxOffsetY)

            launch { scale.animateTo(targetScale, spring()) }
            launch { offsetX.animateTo(clampedX, spring()) }
            launch { offsetY.animateTo(clampedY, spring()) }
        }
    }

    fun onTransform(scope: CoroutineScope, pan: Offset, zoomFactor: Float, size: IntSize, maxZoom: Float) {
        scope.launch {
            val newScale = (scale.value * zoomFactor).coerceIn(1f, maxZoom)
            scale.snapTo(newScale)

            if (scale.value > 1f) {
                val newX = offsetX.value + pan.x
                val newY = offsetY.value + pan.y
                val maxX = (size.width * (scale.value - 1f)) / 2f
                val maxY = (size.height * (scale.value - 1f)) / 2f
                offsetX.snapTo(newX.coerceIn(-maxX, maxX))
                offsetY.snapTo(newY.coerceIn(-maxY, maxY))
            } else {
                offsetX.snapTo(0f)
                offsetY.snapTo(0f)
            }
        }
    }

    fun ensureBounds(screenW: Float, screenH: Float, scope: CoroutineScope) {
        scope.launch {
            val maxX = (screenW * (scale.value - 1f)) / 2f
            val maxY = (screenH * (scale.value - 1f)) / 2f
            launch { offsetX.animateTo(offsetX.value.coerceIn(-maxX, maxX), spring()) }
            launch { offsetY.animateTo(offsetY.value.coerceIn(-maxY, maxY), spring()) }
            if (scale.value < 1f) launch { scale.animateTo(1f, spring()) }
        }
    }
}

class DismissRootState {
    val offsetY = Animatable(0f)
    val scale = Animatable(0.8f)
    val backgroundAlpha = Animatable(0f)

    fun resetInstant(scope: CoroutineScope) {
        scope.launch {
            offsetY.snapTo(0f)
            scale.snapTo(1f)
            backgroundAlpha.snapTo(1f)
        }
    }

    suspend fun animateExit(targetY: Float) = coroutineScope {
        launch {
            backgroundAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 150, easing = LinearEasing)
            )
        }
        launch {
            offsetY.animateTo(
                targetValue = targetY,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
            )
        }
    }

    suspend fun animateRestore() = coroutineScope {
        launch { offsetY.animateTo(0f, tween(150, easing = FastOutSlowInEasing)) }
        launch { scale.animateTo(1f, tween(150)) }
        launch { backgroundAlpha.animateTo(1f, tween(150)) }
    }

    suspend fun drag(delta: Float) {
        val currentY = offsetY.value
        val targetY = currentY + delta
        offsetY.snapTo(targetY)
        val progress = (targetY / 1000f).absoluteValue.coerceIn(0f, 1f)
        scale.snapTo(1f - (progress * 0.15f))
        backgroundAlpha.snapTo(1f - (progress * 0.8f))
    }
}

@Composable
fun rememberZoomState(): ZoomState = remember { ZoomState() }

@Composable
fun rememberDismissRootState(): DismissRootState = remember { DismissRootState() }

suspend fun PointerInputScope.detectZoomAndDismissGestures(
    zoomState: ZoomState,
    rootState: DismissRootState,
    screenHeightPx: Float,
    dismissThreshold: Float,
    dismissVelocityThreshold: Float,
    onDismiss: () -> Unit,
    scope: CoroutineScope
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)

        val touchSlop = viewConfiguration.touchSlop
        var pan = Offset.Zero
        var isZooming = false
        var isVerticalDrag = false

        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break

            val pointerCount = event.changes.size
            event.changes.forEach { tracker.addPointerInputChange(it) }

            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()

            if (pointerCount > 1) isZooming = true

            if (!isZooming && !isVerticalDrag && zoomState.scale.value == 1f && pointerCount == 1) {
                pan += panChange
                val totalPan = pan.getDistance()
                if (totalPan > touchSlop) {
                    if (abs(pan.y) > abs(pan.x) * 2f) {
                        isVerticalDrag = true
                    } else if (abs(pan.x) > touchSlop) {
                        return@awaitEachGesture
                    }
                }
            }

            if (isZooming || zoomState.scale.value > 1f) {
                zoomState.onTransform(scope, panChange, zoomChange, IntSize(size.width, size.height), 3f)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (isVerticalDrag) {
                scope.launch { rootState.drag(panChange.y) }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }

            if (!event.changes.any { it.pressed }) break
        }

        val velocity = tracker.calculateVelocity()
        if (zoomState.scale.value > 1f) {
            zoomState.ensureBounds(size.width.toFloat(), size.height.toFloat(), scope)
        } else if (isVerticalDrag) {
            val offsetY = rootState.offsetY.value
            val shouldDismiss = abs(offsetY) > dismissThreshold || abs(velocity.y) > dismissVelocityThreshold
            if (shouldDismiss) {
                scope.launch {
                    rootState.animateExit(screenHeightPx * sign(offsetY))
                    onDismiss()
                }
            } else {
                scope.launch { rootState.animateRestore() }
            }
        }
    }
}
