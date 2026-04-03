package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.*
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
    isOverCharLimit: Boolean,
    canWriteText: Boolean,
    canSendVoice: Boolean,
    canSendMedia: Boolean,
    isVideoMessageMode: Boolean,
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
    val isSendEnabled =
        (!isTextEmpty || editingMessage != null || pendingMediaPaths.isNotEmpty()) && canWriteText && !isOverCharLimit

    var isVoiceRecordingActive by remember { mutableStateOf(false) }
    val isRecordingMode = isTextEmpty && editingMessage == null && pendingMediaPaths.isEmpty() && canSendVoice

    if (canWriteText || canSendVoice) {
        val sendIcon = when {
            pendingMediaPaths.isNotEmpty() -> Icons.AutoMirrored.Filled.Send
            editingMessage != null -> Icons.Default.Check
            !isTextEmpty -> Icons.AutoMirrored.Filled.Send
            isVideoMessageMode -> Icons.Default.Videocam
            else -> Icons.Outlined.Mic
        }
        val canShowOptions = editingMessage == null && canWriteText &&
                (!isTextEmpty || (pendingMediaPaths.isNotEmpty() && canSendMedia)) &&
                !isOverCharLimit

        Box(
            modifier = Modifier
                .then(
                    if (isRecordingMode) {
                        Modifier
                            .size(48.dp)
                            .background(
                                color = if (isSendEnabled || isVoiceRecordingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                shape = CircleShape
                            )
                            .clip(CircleShape)
                            .pointerInput(isVideoMessageMode) {
                            awaitEachGesture {
                                try {
                                    awaitFirstDown()

                                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                                    val up = withTimeoutOrNull(longPressTimeout) {
                                        waitForUpOrCancellation()
                                    }

                                    if (up == null) {
                                        if (isVideoMessageMode) {
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
                                        onVideoModeToggle()
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                        Modifier
                            .size(48.dp)
                            .background(
                                color = if (isSendEnabled || isVoiceRecordingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                shape = CircleShape
                            )
                            .clip(CircleShape)
                            .combinedClickable(
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
            Crossfade(targetState = sendIcon, label = "IconAnimation") { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSendEnabled || isVoiceRecordingActive) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
