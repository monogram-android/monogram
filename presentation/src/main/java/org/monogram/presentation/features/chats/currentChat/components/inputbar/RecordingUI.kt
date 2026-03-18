package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.presentation.R

@Composable
fun RecordingUI(
    voiceRecorderState: VoiceRecorderState,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val slideToCancelAlpha by animateFloatAsState(
        targetValue = if (voiceRecorderState.isLocked) 0f else 1f,
        animationSpec = tween(300),
        label = "SlideToCancelAlpha"
    )

    val animatedAmplitude by animateFloatAsState(
        targetValue = if (voiceRecorderState.isRecording) {
            1f + (voiceRecorderState.amplitude + 60f) / 60f
        } else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "AmplitudeScale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "RecordingDot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "DotAlpha"
    )

    val lockHintTransition = rememberInfiniteTransition(label = "LockHint")
    val lockHintOffset by lockHintTransition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "LockHintOffset"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(
            targetState = voiceRecorderState.isLocked,
            transitionSpec = {
                fadeIn() + slideInHorizontally { -it } togetherWith fadeOut() + slideOutHorizontally { -it }
            },
            label = "LeftAction"
        ) { isLocked ->
            if (isLocked) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.recording_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Box(modifier = Modifier.width(44.dp))
            }
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(10.dp)
                    .scale(animatedAmplitude)
                    .alpha(dotAlpha)
                    .background(Color.Red, CircleShape)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = formatDuration((voiceRecorderState.durationMillis / 1000).toInt()),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum"
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Box(
            modifier = Modifier.weight(1.5f),
            contentAlignment = Alignment.Center
        ) {
            if (!voiceRecorderState.isLocked) {
                Text(
                    text = stringResource(R.string.recording_slide_to_cancel),
                    modifier = Modifier
                        .alpha(slideToCancelAlpha)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal
                )
            } else {
                WaveformView(
                    waveform = voiceRecorderState.waveform,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(horizontal = 12.dp)
                )
            }
        }

        AnimatedContent(
            targetState = voiceRecorderState.isLocked,
            transitionSpec = {
                (scaleIn() + fadeIn()).togetherWith(scaleOut() + fadeOut())
            },
            label = "RightAction"
        ) { isLocked ->
            if (isLocked) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = stringResource(R.string.recording_send),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .width(64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(14.dp)
                            .offset(y = lockHintOffset.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = stringResource(R.string.recording_lock),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.recording_swipe_up),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun WaveformView(
    waveform: List<Byte>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val barWidth = 2.5.dp
    val gap = 2.dp
    val fadeColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidthPx = barWidth.toPx()
        val gapPx = gap.toPx()
        val totalBarWidthPx = barWidthPx + gapPx

        val maxBars = (width / totalBarWidthPx).toInt()
        val barsToShow = waveform.takeLast(maxBars)

        barsToShow.asReversed().forEachIndexed { index, amplitude ->
            val normalizedAmplitude = (amplitude.toFloat() / 31f).coerceIn(0f, 1f)
            val minHeight = height * 0.15f
            val maxHeight = height * 0.85f
            val barHeight = minHeight + (normalizedAmplitude * (maxHeight - minHeight))

            val x = width - (index + 1) * totalBarWidthPx
            val y = (height - barHeight) / 2

            val alpha = (1f - (index.toFloat() / maxBars)).coerceIn(0.2f, 1f)

            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(x, y),
                size = Size(barWidthPx, barHeight),
                cornerRadius = CornerRadius(barWidthPx / 2, barWidthPx / 2)
            )
        }

        drawRect(
            brush = Brush.horizontalGradient(
                0f to fadeColor,
                0.2f to Color.Transparent,
                startX = 0f,
                endX = width * 0.3f
            ),
            size = Size(width * 0.3f, height)
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}