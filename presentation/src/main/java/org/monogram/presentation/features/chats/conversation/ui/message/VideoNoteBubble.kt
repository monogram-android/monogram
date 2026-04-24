@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import org.koin.compose.koinInject
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.features.chats.conversation.ui.InlineVideoPlayer
import org.monogram.presentation.features.stickers.ui.view.shimmerEffect
import java.io.File
import java.io.FileNotFoundException

@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VideoNoteBubble(
    content: MessageContent.VideoNote,
    msg: MessageModel,
    isOutgoing: Boolean,
    onVideoClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    toProfile: (Long) -> Unit = {},
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val size = 260.dp
    var notePosition by remember { mutableStateOf(Offset.Zero) }
    var isVisible by remember { mutableStateOf(false) }
    val resources = LocalResources.current
    val screenHeightPx = remember { resources.displayMetrics.heightPixels }

    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()

    Column(
        modifier = modifier,
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        msg.forwardInfo?.let { forward ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .widthIn(max = 200.dp)
            ) {
                Box(modifier = Modifier.padding(8.dp)) {
                    ForwardContent(
                        forward,
                        isOutgoing = false,
                        onForwardClick = onForwardOriginClick
                    )
                }
            }
        }
        msg.replyToMsg?.let { reply ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .widthIn(max = 200.dp)
            ) {
                ReplyContent(
                    replyToMsg = reply,
                    isOutgoing = false,
                    onClick = { onReplyClick(reply) }
                )
            }
        }


        Box(
            modifier = Modifier
                .size(size)
                .padding(4.dp)
                .onGloballyPositioned {
                    val rect = it.boundsInWindow()
                    isVisible = rect.bottom > 0 && rect.top < screenHeightPx
                }
        ) {
            var isPlaying by remember { mutableStateOf(false) }
            var isMuted by remember { mutableStateOf(true) }
            var progress by remember { mutableFloatStateOf(0f) }
            var hasError by remember { mutableStateOf(false) }
            val videoPath = content.path
            val canRenderInlineVideo = videoPath?.let { File(it).exists() } == true && !hasError


            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(Color.Black)
                    .onGloballyPositioned { notePosition = it.positionInWindow() }
                    .pointerInput(content.path, content.isDownloading, canRenderInlineVideo) {
                        detectTapGestures(
                            onTap = {
                                if (canRenderInlineVideo) {
                                    isMuted = !isMuted
                                } else if (content.isDownloading) {
                                    onCancelDownload(content.fileId)
                                } else {
                                    onVideoClick(msg)
                                }
                            },
                            onLongPress = { offset -> onLongClick(notePosition + offset) }
                        )
                    }
            ) {
                if (canRenderInlineVideo) {
                    val resolvedVideoPath = requireNotNull(videoPath)
                    LaunchedEffect(isVisible) {
                        if (isVisible) {
                            isPlaying = true
                        }
                    }

                    InlineVideoPlayer(
                        path = resolvedVideoPath,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                        animate = isPlaying && isVisible,
                        volume = if (isMuted) 0f else 1f,
                        placeholderData = content.thumbnail,
                        reportProgress = true,
                        onProgressUpdate = { positionMs ->
                            val durationMs = (content.duration * 1000L).coerceAtLeast(0L)
                            progress = if (durationMs > 0L) {
                                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        onPlaybackError = { error ->
                            if (error.cause is FileNotFoundException) {
                                hasError = true
                            }
                        }
                    )
                } else {

                    if (content.thumbnail != null) {
                        Image(
                            painter = rememberAsyncImagePainter(content.thumbnail),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .shimmerEffect(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (content.isDownloading) {
                                CircularWavyProgressIndicator(
                                    progress = { content.downloadProgress },
                                    color = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = stringResource(R.string.cd_download),
                                    modifier = Modifier.size(48.dp),
                                    tint = Color.White.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }


                if (canRenderInlineVideo && isPlaying) {
                    Box(modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(R.string.menu_mute),
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }


            if (content.isUploading) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    CircularWavyProgressIndicator(
                        progress = { content.uploadProgress },
                        color = Color.White,
                        modifier = Modifier.size(48.dp),
                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                }
            } else if (canRenderInlineVideo) {

                CircularWavyProgressIndicator(
                    progress = { progress },
                    color = Color.White,
                    modifier = Modifier.matchParentSize(),
                    trackColor = Color.Transparent
                )
            }


            if (!isPlaying && canRenderInlineVideo) {
                Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.action_play),
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(0.4f), CircleShape)
                            .padding(8.dp)
                            .clickable { isPlaying = true },
                        tint = Color.White
                    )
                }
            }


            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatDuration(content.duration),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = Color.White
                )
            }


            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (msg.editDate > 0) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.info_edited),
                            modifier = Modifier.size(12.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = formatTime(msg.date, timeFormat),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = Color.White
                    )
                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (msg.isRead) Icons.Default.DoneAll else Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color.White
                        )
                    }
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
