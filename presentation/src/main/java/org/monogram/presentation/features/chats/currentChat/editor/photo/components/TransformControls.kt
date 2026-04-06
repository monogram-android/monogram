package org.monogram.presentation.features.chats.currentChat.editor.photo.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.presentation.R
import kotlin.math.absoluteValue
import kotlin.math.floor
import kotlin.math.roundToInt

private const val ResetEasterEggTapCount = 5
private const val ResetEasterEggTapWindowMs = 450L

@Composable
fun TransformControls(
    rotation: Float,
    onRotationChange: (Float) -> Unit,
    onSpin: () -> Unit,
    isSpinning: Boolean,
    onReset: () -> Unit
) {
    val normalizedRotation = normalizeRotation(rotation)
    var rapidResetTapCount by remember { mutableIntStateOf(0) }
    var lastResetTapAtMs by remember { mutableLongStateOf(0L) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${normalizedRotation.roundToInt()}°",
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                RotationWheel(
                    angle = rotation,
                    onAngleChange = onRotationChange,
                    enabled = !isSpinning,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            OutlinedIconButton(
                onClick = {
                    val now = SystemClock.uptimeMillis()
                    val tapCount = if (now - lastResetTapAtMs <= ResetEasterEggTapWindowMs) {
                        rapidResetTapCount + 1
                    } else {
                        1
                    }

                    lastResetTapAtMs = now
                    rapidResetTapCount = tapCount
                    onReset()

                    if (tapCount >= ResetEasterEggTapCount) {
                        rapidResetTapCount = 0
                        lastResetTapAtMs = 0L
                        onSpin()
                    }
                },
                enabled = !isSpinning,
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.photo_editor_action_reset)
                )
            }
        }
    }
}

@Composable
private fun RotationWheel(
    angle: Float,
    onAngleChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    var visualAngle by remember { mutableFloatStateOf(angle) }

    LaunchedEffect(angle) {
        visualAngle = closestEquivalentAngle(angle, visualAngle)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.large
                )
                .pointerInput(Unit) {
                    if (!enabled) return@pointerInput
                    var dragAngle = visualAngle
                    detectDragGestures(
                        onDragStart = {
                            dragAngle = visualAngle
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        dragAngle -= dragAmount.x * 0.1f
                        visualAngle = dragAngle
                        onAngleChange(dragAngle)
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(42.dp)) {
                val centerX = size.width / 2f
                val bottom = size.height
                val tickSpacing = 6f
                val minorStep = 5
                val majorStep = 45
                val currentTick = visualAngle / minorStep
                val centerTick = floor(currentTick).toInt()
                val visibleTickCount = (size.width / tickSpacing / 2f).roundToInt() + 4

                for (relativeTick in -visibleTickCount..visibleTickCount) {
                    val tickIndex = centerTick + relativeTick
                    val tickAngle = tickIndex * minorStep
                    val x = centerX + (tickIndex - currentTick) * tickSpacing
                    if (x < 0f || x > size.width) continue

                    val distanceRatio = ((x - centerX).absoluteValue / centerX).coerceIn(0f, 1f)
                    val alpha = 1f - distanceRatio * 0.8f
                    val isMajor = tickAngle % majorStep == 0
                    val isMedium = tickAngle % 15 == 0
                    val tickHeight = when {
                        isMajor -> size.height * 0.62f
                        isMedium -> size.height * 0.45f
                        else -> size.height * 0.28f
                    }
                    val strokeWidth = when {
                        isMajor -> 3f
                        isMedium -> 2.5f
                        else -> 1.5f
                    }

                    drawLine(
                        color = onSurface.copy(alpha = alpha),
                        start = Offset(x, bottom - tickHeight),
                        end = Offset(x, bottom),
                        strokeWidth = strokeWidth
                    )
                }

                drawLine(
                    color = primary,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, bottom),
                    strokeWidth = 4f
                )
            }
        }
    }
}

private fun normalizeRotation(value: Float): Float {
    var normalized = value % 360f
    if (normalized > 180f) normalized -= 360f
    if (normalized < -180f) normalized += 360f
    return normalized
}

private fun closestEquivalentAngle(normalizedAngle: Float, referenceAngle: Float): Float {
    val turns = ((referenceAngle - normalizedAngle) / 360f).roundToInt()
    return normalizedAngle + turns * 360f
}
