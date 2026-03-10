package org.monogram.presentation.features.viewers.components

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.max

@Composable
fun Modifier.videoGestures(
    exoPlayer: ExoPlayer,
    isLocked: Boolean,
    isInPipMode: Boolean,
    isDoubleTapSeekEnabled: Boolean,
    isGesturesEnabled: Boolean,
    isZoomEnabled: Boolean,
    seekDurationMs: Long,
    zoomState: ZoomState,
    rootState: DismissRootState,
    screenHeightPx: Float,
    dismissDistancePx: Float,
    dismissVelocityThreshold: Float,
    onDismiss: () -> Unit,
    onToggleControls: () -> Unit,
    onGestureOverlayChange: (Boolean, ImageVector?, String?) -> Unit,
    onSeekFeedback: (Boolean, Boolean) -> Unit,
    context: Context
): Modifier {
    val scope = rememberCoroutineScope()

    return this
        .pointerInput(isLocked, isInPipMode) {
            if (isInPipMode) return@pointerInput
            detectTapGestures(
                onDoubleTap = { offset ->
                    if (zoomState.scale.value > 1f) {
                        zoomState.onDoubleTap(scope, offset, 1f, size)
                    } else if (!isLocked && isDoubleTapSeekEnabled) {
                        val width = size.width
                        if (offset.x < width / 2) {
                            exoPlayer.seekTo(max(0, exoPlayer.currentPosition - seekDurationMs))
                            onSeekFeedback(true, true)
                        } else {
                            exoPlayer.seekTo(exoPlayer.currentPosition + seekDurationMs)
                            onSeekFeedback(true, false)
                        }
                    }
                },
                onTap = { onToggleControls() }
            )
        }
        .pointerInput(isLocked, isInPipMode) {
            if (isInPipMode) return@pointerInput
            detectVerticalDragGestures(
                onDragStart = { change ->
                    if (!isLocked && isGesturesEnabled && zoomState.scale.value == 1f) {
                        val width = size.width
                        val x = change.x
                        // Only show overlay if dragging on the edges (15% width)
                        if (x < width * 0.15f || x > width * 0.85f) {
                            onGestureOverlayChange(true, null, null)
                        }
                    }
                },
                onDragEnd = { onGestureOverlayChange(false, null, null) },
                onDragCancel = { onGestureOverlayChange(false, null, null) }
            ) { change, dragAmount ->
                if (!isLocked && isGesturesEnabled && zoomState.scale.value == 1f) {
                    val width = size.width
                    val x = change.position.x
                    val isLeft = x < width * 0.15f
                    val isRight = x > width * 0.85f
                    val activity = context.findActivity()

                    if (isLeft && activity != null) {
                        val lp = activity.window.attributes
                        var newBrightness = (lp.screenBrightness.takeIf { it != -1f } ?: 0.5f) - (dragAmount / 1000f)
                        newBrightness = newBrightness.coerceIn(0f, 1f)
                        lp.screenBrightness = newBrightness
                        activity.window.attributes = lp
                        onGestureOverlayChange(
                            true,
                            Icons.Rounded.BrightnessMedium,
                            "${(newBrightness * 100).toInt()}%"
                        )
                    } else if (isRight) {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val delta = -(dragAmount / 50f)
                        val newVol = (currentVol + delta).coerceIn(0f, maxVol.toFloat())
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol.toInt(), 0)
                        onGestureOverlayChange(
                            true,
                            Icons.AutoMirrored.Rounded.VolumeUp,
                            "${((newVol / maxVol) * 100).toInt()}%"
                        )
                    }
                }
            }
        }
        .pointerInput(isLocked, isInPipMode) {
            if (isInPipMode) return@pointerInput
            detectZoomAndDismissGestures(
                zoomState = zoomState,
                rootState = rootState,
                screenHeightPx = screenHeightPx,
                dismissThreshold = dismissDistancePx,
                dismissVelocityThreshold = dismissVelocityThreshold,
                onDismiss = onDismiss,
                scope = scope
            )
        }
}
