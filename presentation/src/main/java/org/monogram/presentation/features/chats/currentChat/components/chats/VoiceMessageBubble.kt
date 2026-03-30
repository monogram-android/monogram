package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.rememberVoicePlayer

@Composable
fun VoiceMessageBubble(
    content: MessageContent.Voice,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    autoDownloadFiles: Boolean,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    onVoiceClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    isGroup: Boolean = false,
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    downloadUtils: IDownloadUtils
) {
    val cornerRadius = 18.dp
    val smallCorner = 4.dp
    val tailCorner = 2.dp

    LaunchedEffect(
        content.path,
        content.isDownloading,
        autoDownloadFiles,
        autoDownloadMobile,
        autoDownloadWifi,
        autoDownloadRoaming
    ) {
        val shouldDownload = if (autoDownloadFiles) {
            when {
                downloadUtils.isRoaming() -> autoDownloadRoaming
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                else -> autoDownloadMobile
            }
        } else {
            false
        }

        if (shouldDownload && content.path == null && !content.isDownloading) {
            onVoiceClick(msg)
        }
    }

    val bubbleShape = RoundedCornerShape(
        topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        bottomStart = if (!isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius,
        bottomEnd = if (isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius
    )

    val backgroundColor =
        if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = contentColor.copy(alpha = 0.7f)

    var bubblePosition by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = modifier
            .onGloballyPositioned { bubblePosition = it.positionInWindow() },
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 184.dp, max = 300.dp)
            ) {
                if (isGroup && !isOutgoing && !isSameSenderAbove) {
                    MessageSenderName(msg, toProfile = toProfile)
                }

                msg.forwardInfo?.let { forward ->
                    Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                        ForwardContent(forward, isOutgoing, onForwardClick = toProfile)
                    }
                }
                msg.replyToMsg?.let { reply ->
                    Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = isOutgoing,
                            onClick = { onReplyClick(reply) }
                        )
                    }
                }

                VoiceRow(
                    content = content,
                    msg = msg,
                    onVoiceClick = onVoiceClick,
                    onCancelDownload = onCancelDownload,
                    isOutgoing = isOutgoing
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    MessageMetadata(msg, isOutgoing, timeColor)
                }
            }
        }

        MessageReactionsView(
            reactions = msg.reactions,
            onReactionClick = onReactionClick,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun VoiceRow(
    content: MessageContent.Voice,
    msg: MessageModel,
    onVoiceClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    isOutgoing: Boolean
) {
    val playerState = rememberVoicePlayer(content.path)
    playerState.ProgressUpdater()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
                .clickable {
                    if (content.isDownloading) {
                        onCancelDownload(content.fileId)
                    } else if (content.path == null) {
                        onVoiceClick(msg)
                    } else {
                        playerState.togglePlayPause()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (content.isDownloading || content.isUploading) {
                CircularProgressIndicator(
                    progress = { if (content.isDownloading) content.downloadProgress else content.uploadProgress },
                    modifier = Modifier.size(32.dp),
                    color = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    trackColor = (if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onPrimary).copy(
                        alpha = 0.2f
                    ),
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                val icon = when {
                    content.path == null -> Icons.Default.Download
                    playerState.isPlaying -> Icons.Default.Pause
                    else -> Icons.Default.PlayArrow
                }
                Icon(
                    imageVector = icon,
                    contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                    tint = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            WaveformView(
                waveform = content.waveform,
                progress = playerState.progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                activeColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                inactiveColor = (if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant).copy(
                    alpha = 0.3f
                )
            )

            Text(
                text = if (playerState.isPlaying || playerState.progress > 0) {
                    "${formatDuration((playerState.currentPosition / 1000).toInt())} / ${formatDuration(content.duration)}"
                } else {
                    formatDuration(content.duration)
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = (if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant).copy(
                    alpha = 0.7f
                )
            )
        }
    }
}

@Composable
fun WaveformView(
    waveform: ByteArray?,
    progress: Float,
    modifier: Modifier = Modifier,
    activeColor: Color,
    inactiveColor: Color
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = 2.dp.toPx()
        val gap = 1.dp.toPx()
        val totalBarWidth = barWidth + gap

        val barsCount = (width / totalBarWidth).toInt()

        if (waveform == null || waveform.isEmpty()) {
            for (i in 0 until barsCount) {
                val barHeight = (height * 0.2f).coerceAtLeast(2.dp.toPx())
                val x = i * totalBarWidth
                val color = if (x / width <= progress) activeColor else inactiveColor
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, (height - barHeight) / 2),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2)
                )
            }
            return@Canvas
        }

        val samples = decodeWaveform(waveform, barsCount)

        samples.forEachIndexed { i, sample ->
            val barHeight = (height * (sample / 31f)).coerceAtLeast(2.dp.toPx())
            val x = i * totalBarWidth
            val color = if (i.toFloat() / barsCount <= progress) activeColor else inactiveColor

            drawRoundRect(
                color = color,
                topLeft = Offset(x, (height - barHeight) / 2),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2)
            )
        }
    }
}

private fun decodeWaveform(waveform: ByteArray, count: Int): List<Int> {
    val bitsCount = waveform.size * 8
    val samplesCount = bitsCount / 5
    if (samplesCount == 0) return List(count) { 0 }

    val result = mutableListOf<Int>()
    for (i in 0 until samplesCount) {
        var val5bit = 0
        for (j in 0 until 5) {
            val bitIndex = i * 5 + j
            val byteIndex = bitIndex / 8
            val bitInByte = bitIndex % 8
            if ((waveform[byteIndex].toInt() and (1 shl bitInByte)) != 0) {
                val5bit = val5bit or (1 shl j)
            }
        }
        result.add(val5bit)
    }

    if (result.size == count) return result

    val resampled = mutableListOf<Int>()
    for (i in 0 until count) {
        val index = (i.toFloat() / count * result.size).toInt().coerceIn(0, result.size - 1)
        resampled.add(result[index])
    }
    return resampled
}
