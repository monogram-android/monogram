package org.monogram.core.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.components.mediaTime

/**
 * Material 3 Expressive seek bar: a vertical-bar handle instead of a circle, a gap
 * between the active and inactive track, a buffered second track, a stop dot at the
 * end, and a wavy active track that only flows while the video is actually playing.
 *
 * The bar is always laid out left-to-right, even in RTL locales, so the timeline
 * direction never flips underneath the user.
 */
@Composable
fun MediaSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    playing: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 6.dp,
    handleSweep: Boolean = true,
) {
    val reducedMotion = !mediaViewerMotionEnabled()
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val safeDuration = durationMs.coerceAtLeast(1L)
    val idleFraction = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val fraction = if (scrubbing) scrubFraction else idleFraction
    val bufferedFraction = (bufferedMs.toFloat() / safeDuration).coerceIn(0f, 1f)

    val colors = MaterialTheme.colorScheme
    val activeColor = colors.primary
    val inactiveColor = colors.onSurface.copy(alpha = 0.24f)
    val bufferedColor = colors.secondaryContainer
    val handleColor = colors.primary

    // Flattening is animated, so pausing settles instead of snapping.
    val waveAmount by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (playing && !scrubbing && !reducedMotion) 1f else 0f,
        animationSpec = MediaMotion.effects(reducedMotion),
        label = "seekWaveAmount",
    )
    val handleGrowth by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (scrubbing) 1.18f else 1f,
        animationSpec = MediaMotion.spatial(reducedMotion),
        label = "seekHandle",
    )
    val transition = rememberInfiniteTransition(label = "seekWave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "seekWavePhase",
    )

    fun fractionAt(x: Float): Float =
        if (size.width <= 0) 0f else (x / size.width).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { size = it }
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = if (scrubbing) (scrubFraction * durationMs) else positionMs.toFloat(),
                    range = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                    steps = 0,
                )
                if (!enabled) {
                    disabled()
                } else {
                    setProgress { target ->
                        onScrubEnd(target.toLong())
                        true
                    }
                }
            }
            .pointerInput(enabled, durationMs) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val target = (fractionAt(offset.x) * durationMs).toLong()
                    onScrubEnd(target)
                }
            }
            .pointerInput(enabled, durationMs) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        scrubbing = true
                        scrubFraction = fractionAt(offset.x)
                        onScrubStart()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        scrubFraction = fractionAt(change.position.x)
                        onScrub((scrubFraction * durationMs).toLong())
                    },
                    onDragEnd = {
                        scrubbing = false
                        onScrubEnd((scrubFraction * durationMs).toLong())
                    },
                    onDragCancel = {
                        scrubbing = false
                        onScrubEnd((scrubFraction * durationMs).toLong())
                    },
                )
            },
    ) {
        val label = mediaTime(if (scrubbing) (scrubFraction * durationMs).toLong() else positionMs)
        val showIndicator = scrubbing && handleSweep
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val stroke = trackHeight.toPx()
            val centerY = this.size.height / 2f
            val inset = stroke / 2f + 1f
            val usable = (this.size.width - inset * 2).coerceAtLeast(1f)
            val handleX = inset + usable * fraction

            // Inactive track, inset by a gap on both sides of the handle.
            val gap = stroke * 0.9f
            val inactiveStart = (handleX + gap).coerceAtMost(inset + usable)
            drawLine(
                color = inactiveColor,
                start = Offset(inactiveStart, centerY),
                end = Offset(inset + usable, centerY),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            // Buffered second track sits under the active one.
            val bufferedEnd = inset + usable * bufferedFraction
            if (bufferedEnd > inset) {
                drawLine(
                    color = bufferedColor,
                    start = Offset(inset, centerY),
                    end = Offset(bufferedEnd, centerY),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            // Active track: wavy while playing, flat while paused or dragging. The
            // amplitude animates between the two so the transition is a settle, not a cut.
            val activeEnd = (handleX - gap).coerceAtLeast(inset)
            if (activeEnd > inset) {
                drawWave(inset, activeEnd, centerY, stroke, phase, activeColor, waveAmount)
            }
            // Stop dot marks the end of the timeline.
            drawCircle(
                color = inactiveColor,
                radius = stroke * 0.75f,
                center = Offset(inset + usable, centerY),
            )
            // Vertical-bar handle: M3E replaces the circle with a rounded bar.
            val handleWidth = stroke * 0.65f * handleGrowth
            val handleHeight = stroke * 3.2f * handleGrowth
            drawRoundRect(
                color = handleColor,
                topLeft = Offset(handleX - handleWidth / 2f, centerY - handleHeight / 2f),
                size = Size(handleWidth, handleHeight),
                cornerRadius = CornerRadius(handleWidth / 2f, handleWidth / 2f),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showIndicator,
            enter = fadeIn(tween(if (reducedMotion) 0 else 120)) +
                scaleIn(initialScale = 0.85f, animationSpec = MediaMotion.spatial(reducedMotion)),
            exit = fadeOut(tween(if (reducedMotion) 0 else 90)) +
                scaleOut(targetScale = 0.85f, animationSpec = MediaMotion.effects(reducedMotion)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colors.inverseSurface,
                contentColor = colors.inverseOnSurface,
                modifier = Modifier.graphicsLayer {
                    translationX = (fraction - 0.5f) * size.width
                },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

private fun DrawScope.drawWave(
    startX: Float,
    endX: Float,
    centerY: Float,
    stroke: Float,
    phase: Float,
    color: Color,
    amount: Float = 1f,
) {
    val amplitude = stroke * 0.55f * amount
    if (amount <= 0.01f) {
        drawLine(
            color = color,
            start = Offset(startX, centerY),
            end = Offset(endX, centerY),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        return
    }
    val wavelength = stroke * 4.2f
    val path = Path()
    path.moveTo(startX, centerY)
    var x = startX
    while (x <= endX) {
        val angle = ((x - startX) / wavelength + phase) * 2f * Math.PI
        val y = centerY + (kotlin.math.sin(angle).toFloat() * amplitude)
        path.lineTo(x, y)
        x += wavelength / 8f
    }
    path.lineTo(endX, centerY)
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
    )
}

/** 2dp primary tick left on the bottom edge after the chrome hides. */
@Composable
fun MediaProgressTick(
    fraction: Float,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    barHeight: Dp = 12.dp,
) {
    val colors = MaterialTheme.colorScheme
    val reducedMotion = !mediaViewerMotionEnabled()
    val transition = rememberInfiniteTransition(label = "tickWave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "tickPhase",
    )
    Canvas(modifier.fillMaxWidth().height(barHeight)) {
        val centerY = size.height / 2f
        val usable = size.width
        val end = (usable * fraction).coerceIn(0f, usable)
        drawLine(
            color = colors.onSurface.copy(alpha = 0.28f),
            start = Offset(0f, centerY),
            end = Offset(usable, centerY),
            strokeWidth = 2.dp.toPx(),
        )
        if (end > 0f) {
            val stroke = 2.dp.toPx()
            if (playing && !reducedMotion) {
                drawWave(0f, end, centerY, stroke, phase, colors.primary)
            } else {
                drawLine(
                    color = colors.primary,
                    start = Offset(0f, centerY),
                    end = Offset(end, centerY),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}
