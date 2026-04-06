package org.monogram.presentation.features.chats.currentChat.editor.photo.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
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

@Composable
fun TransformControls(
    rotation: Float,
    onRotationChange: (Float) -> Unit,
    onRotateClockwise: () -> Unit,
    onReset: () -> Unit
) {
    val normalizedRotation = normalizeRotationDegrees(rotation)

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
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedIconButton(
                    onClick = onRotateClockwise,
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        Icons.Rounded.Rotate90DegreesCw,
                        contentDescription = stringResource(R.string.photo_editor_action_rotate_right)
                    )
                }

                OutlinedIconButton(
                    onClick = onReset,
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        contentColor = MaterialTheme.colorScheme.onSurface
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
}

@Composable
private fun RotationWheel(
    angle: Float,
    onAngleChange: (Float) -> Unit,
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

internal fun normalizeRotationDegrees(value: Float): Float {
    var normalized = value % 360f
    if (normalized > 180f) normalized -= 360f
    if (normalized < -180f) normalized += 360f
    return normalized
}

internal fun rotateClockwiseAnimationTarget(value: Float): Float {
    val snappedRotation = (value / 90f).roundToInt() * 90f
    return snappedRotation + 90f
}

internal fun rotateClockwiseToNextRightAngle(value: Float): Float {
    return normalizeRotationDegrees(rotateClockwiseAnimationTarget(value))
}

private fun closestEquivalentAngle(normalizedAngle: Float, referenceAngle: Float): Float {
    val turns = ((referenceAngle - normalizedAngle) / 360f).roundToInt()
    return normalizedAngle + turns * 360f
}
