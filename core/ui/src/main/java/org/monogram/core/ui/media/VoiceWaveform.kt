package org.monogram.core.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun VoiceWaveform(
    amplitudes: List<Float>,
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    contentDescription: String,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
) {
    val duration = durationMs.coerceAtLeast(0L)
    val seekable = enabled && duration > 0
    val currentOnSeek by rememberUpdatedState(onSeek)
    var scrubFraction by remember(duration, seekable) { mutableStateOf<Float?>(null) }
    val positionFraction = if (duration > 0) {
        (positionMs.toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }
    val fraction = scrubFraction ?: positionFraction
    val animatedFraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "voice-progress",
    )
    val waveTransition = rememberInfiniteTransition(label = "voice-wave")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "voice-wave-phase",
    )
    val colors = MaterialTheme.colorScheme
    val playedColor = colors.primary.copy(alpha = if (enabled) 1f else 0.38f)
    val neutralColor = colors.onSurfaceVariant.copy(alpha = if (enabled) 0.45f else 0.24f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = (fraction.toDouble() * duration).toFloat(),
                    range = 0f..duration.coerceAtLeast(1L).toFloat(),
                )
                if (!seekable) {
                    disabled()
                } else {
                    setProgress { target ->
                        if (target.isFinite()) {
                            currentOnSeek(target.toLong().coerceIn(0L, duration))
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            .focusable(enabled = seekable)
            .pointerInput(seekable, duration) {
                if (!seekable) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun seekAt(x: Float) {
                        val target = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        scrubFraction = target
                        currentOnSeek((target.toDouble() * duration).toLong().coerceIn(0L, duration))
                    }
                    try {
                        val slop = awaitTouchSlopOrCancellation(down.id) { change, over -> if (abs(over.x) > abs(over.y)) change.consume() }
                        if (slop == null || abs(slop.position.x - down.position.x) <= abs(slop.position.y - down.position.y)) return@awaitEachGesture
                        seekAt(slop.position.x)
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            change.consume()
                            seekAt(change.position.x)
                        } while (change.pressed)
                    } finally {
                        scrubFraction = null
                    }
                }
            },
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val count = 64
        val step = size.width / count
        val stroke = minOf(3.dp.toPx(), step * 0.6f)
        val minimumHeight = minOf(3.dp.toPx(), size.height)
        val maximumHeight = minOf(32.dp.toPx(), size.height)
        repeat(count) { index ->
            val sample = if (amplitudes.isEmpty()) {
                0f
            } else {
                amplitudes[(index.toLong() * amplitudes.size / count).toInt()]
                    .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            }
            val waveLift = if (playing) {
                1f + 0.08f * kotlin.math.sin(index * 0.55f + wavePhase * 6.283f)
            } else 1f
            val barHeight = (minimumHeight + (maximumHeight - minimumHeight) * sample) * waveLift
            val x = (index + 0.5f) * step
            drawLine(
                color = if (amplitudes.isNotEmpty() && x < size.width * animatedFraction) {
                    playedColor
                } else {
                    neutralColor
                },
                start = Offset(x, (size.height - barHeight) / 2f),
                end = Offset(x, (size.height + barHeight) / 2f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
