package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendOptions

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InputBarSendButton(
    textValue: TextFieldValue,
    editingMessage: MessageModel?,
    pendingMediaPaths: List<String>,
    pendingDocumentPaths: List<String>,
    isOverCharLimit: Boolean,
    canWriteText: Boolean,
    canSendVoice: Boolean,
    canSendVideoNotes: Boolean,
    canSendMedia: Boolean,
    isVideoMessageMode: Boolean,
    isSlowModeActive: Boolean,
    slowModeRemainingSeconds: Int,
    onSendWithOptions: (MessageSendOptions) -> Unit,
    onShowSendOptionsMenu: () -> Unit,
    onCameraClick: () -> Unit,
    onVideoModeToggle: () -> Unit,
    onVoiceStart: () -> Unit = {},
    onVoiceStop: (Boolean) -> Unit = { _ -> },
    onVoiceLock: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val isTextEmpty = textValue.text.isBlank()
    val hasPendingAttachments = pendingMediaPaths.isNotEmpty() || pendingDocumentPaths.isNotEmpty()
    val canSendContent = canWriteText || (hasPendingAttachments && canSendMedia)
    val isSlowModeBlocked = isSlowModeActive && editingMessage == null
    val isSendEnabled =
        (!isTextEmpty || editingMessage != null || hasPendingAttachments) &&
                canSendContent &&
                !isOverCharLimit &&
                !isSlowModeBlocked

    var isVoiceRecordingActive by remember { mutableStateOf(false) }
    val effectiveVideoMode = when {
        !canSendVideoNotes -> false
        !canSendVoice -> true
        else -> isVideoMessageMode
    }
    val canUseRecording = canSendVoice || canSendVideoNotes
    val canToggleRecordingMode = canSendVoice && canSendVideoNotes
    val isRecordingMode =
        isTextEmpty && editingMessage == null && !hasPendingAttachments && canUseRecording && !isSlowModeBlocked

    val backgroundColor by animateColorAsState(
        targetValue = if (isSendEnabled || isVoiceRecordingActive || isSlowModeBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(250),
        label = "BackgroundColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSendEnabled || isVoiceRecordingActive || isSlowModeBlocked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(250),
        label = "ContentColor"
    )

    if (canWriteText || canSendVoice || canSendVideoNotes) {
        val sendIcon = when {
            hasPendingAttachments -> Icons.AutoMirrored.Filled.Send
            editingMessage != null -> Icons.Default.Check
            !isTextEmpty -> Icons.AutoMirrored.Filled.Send
            effectiveVideoMode -> Icons.Default.Videocam
            else -> Icons.Outlined.Mic
        }
        val canShowOptions = editingMessage == null && canWriteText &&
                (!isTextEmpty || (hasPendingAttachments && canSendMedia)) &&
                !isOverCharLimit &&
                !isSlowModeBlocked

        Box(
            modifier = Modifier
                .size(48.dp)
                .background(color = backgroundColor, shape = CircleShape)
                .clip(CircleShape)
                .then(
                    if (isRecordingMode) {
                        Modifier.pointerInput(effectiveVideoMode, canToggleRecordingMode) {
                            awaitEachGesture {
                                try {
                                    awaitFirstDown()

                                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                                    val up = withTimeoutOrNull(longPressTimeout) {
                                        waitForUpOrCancellation()
                                    }

                                    if (up == null) {
                                        if (effectiveVideoMode) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onCameraClick()
                                            waitForUpOrCancellation()
                                        } else {
                                            isVoiceRecordingActive = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onVoiceStart()

                                            var totalDrag = Offset.Zero
                                            val lockThreshold = -80.dp.toPx()
                                            val cancelThreshold = -80.dp.toPx()

                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val changes = event.changes

                                                changes.forEach { change ->
                                                    totalDrag += change.positionChange()
                                                    change.consume()
                                                }

                                                if (totalDrag.y < lockThreshold) {
                                                    isVoiceRecordingActive = false
                                                    onVoiceLock()
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    break
                                                } else if (totalDrag.x < cancelThreshold) {
                                                    isVoiceRecordingActive = false
                                                    onVoiceStop(true)
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    break
                                                }

                                                if (event.type == PointerEventType.Release || changes.all { !it.pressed }) {
                                                    isVoiceRecordingActive = false
                                                    onVoiceStop(false)
                                                    break
                                                }
                                            }
                                        }
                                    } else {
                                        if (canToggleRecordingMode) {
                                            onVideoModeToggle()
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                } finally {
                                    if (isVoiceRecordingActive) {
                                        isVoiceRecordingActive = false
                                        onVoiceStop(true)
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier.combinedClickable(
                            onClick = {
                                if (isSendEnabled) {
                                    onSendWithOptions(MessageSendOptions())
                                }
                            },
                            onLongClick = {
                                if (canShowOptions) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onShowSendOptionsMenu()
                                }
                            }
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSlowModeBlocked) {
                Text(
                    text = formatSlowModeCountdown(slowModeRemainingSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor
                )
            } else {
                AnimatedContent(
                    targetState = sendIcon,
                    transitionSpec = {
                        val enteringSend =
                            targetState == Icons.AutoMirrored.Filled.Send || targetState == Icons.Default.Check
                        val leavingSend =
                            initialState == Icons.AutoMirrored.Filled.Send || initialState == Icons.Default.Check

                        when {
                            enteringSend && !leavingSend -> {
                                (fadeIn(animationSpec = tween(220, delayMillis = 50)) + scaleIn(
                                    initialScale = 0.4f,
                                    animationSpec = tween(220, delayMillis = 50)
                                ) + slideInVertically { it / 2 })
                                    .togetherWith(
                                        fadeOut(animationSpec = tween(180)) + scaleOut(
                                            targetScale = 0.4f,
                                            animationSpec = tween(180)
                                        ) + slideOutVertically { -it / 2 })
                            }

                            !enteringSend && leavingSend -> {
                                (fadeIn(animationSpec = tween(220, delayMillis = 50)) + scaleIn(
                                    initialScale = 0.4f,
                                    animationSpec = tween(220, delayMillis = 50)
                                ) + slideInVertically { -it / 2 })
                                    .togetherWith(
                                        fadeOut(animationSpec = tween(180)) + scaleOut(
                                            targetScale = 0.4f,
                                            animationSpec = tween(180)
                                        ) + slideOutVertically { it / 2 })
                            }

                            else -> {
                                (fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.8f)).togetherWith(
                                    fadeOut(
                                        animationSpec = tween(200)
                                    ) + scaleOut(targetScale = 0.8f)
                                )
                            }
                        }.using(SizeTransform(clip = false))
                    },
                    label = "IconAnimation"
                ) { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

private fun formatSlowModeCountdown(totalSeconds: Int): String {
    val clamped = totalSeconds.coerceAtLeast(0)
    val hours = clamped / 3600
    val minutes = (clamped % 3600) / 60
    val seconds = clamped % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
