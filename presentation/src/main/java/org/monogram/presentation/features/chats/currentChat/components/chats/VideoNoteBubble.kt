@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.chats.currentChat.components.chats

import android.net.Uri
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.getMimeType
import org.monogram.presentation.features.stickers.ui.view.shimmerEffect
import java.io.File
import java.io.FileNotFoundException

@OptIn(UnstableApi::class, ExperimentalMaterial3ExpressiveApi::class)
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
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
    modifier: Modifier = Modifier
) {
    val size = 260.dp
    var notePosition by remember { mutableStateOf(Offset.Zero) }

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
                    ForwardContent(forward, isOutgoing = false, onForwardClick = toProfile)
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
        ) {
            val context = LocalContext.current
            var isPlaying by remember { mutableStateOf(false) }
            var isMuted by remember { mutableStateOf(true) }
            var progress by remember { mutableFloatStateOf(0f) }
            var hasError by remember { mutableStateOf(false) }


            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(Color.Black)
                    .onGloballyPositioned { notePosition = it.positionInWindow() }
                    .pointerInput(content.path, content.isDownloading, hasError) {
                        detectTapGestures(
                            onTap = {
                                if (content.path != null && !hasError) {
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
                if (content.path != null && File(content.path).exists() && !hasError) {
                    val exoPlayer = remember {
                        val extractorsFactory = DefaultExtractorsFactory()
                            .setConstantBitrateSeekingEnabled(true)
                            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

                        ExoPlayer.Builder(context)
                            .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
                            .build().apply {
                            repeatMode = Player.REPEAT_MODE_ONE
                                val mediaItem = MediaItem.Builder()
                                    .setUri(Uri.parse(content.path))
                                    .setMimeType(getMimeType(content.path!!) ?: MimeTypes.VIDEO_MP4)
                                    .build()
                                setMediaItem(mediaItem)
                            prepare()
                            volume = 0f
                            playWhenReady = true
                        }
                    }

                    DisposableEffect(exoPlayer) {
                        val listener = object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                if (error.cause is FileNotFoundException) {
                                    hasError = true
                                }
                            }
                        }
                        exoPlayer.addListener(listener)
                        onDispose {
                            exoPlayer.removeListener(listener)
                            exoPlayer.release()
                        }
                    }

                    LaunchedEffect(isMuted) {
                        exoPlayer.volume = if (isMuted) 0f else 1f
                    }

                    LaunchedEffect(isPlaying) {
                        if (isPlaying) exoPlayer.play() else exoPlayer.pause()
                    }

                    LaunchedEffect(Unit) { isPlaying = true }


                    LaunchedEffect(isPlaying) {
                        while (isActive && isPlaying) {
                            val current = exoPlayer.currentPosition
                            val total = exoPlayer.duration
                            if (total > 0) {
                                progress = current.toFloat() / total.toFloat()
                            }
                            delay(50)
                        }
                    }

                    Box(
                        modifier = Modifier.matchParentSize()
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                                }
                            },
                            modifier = Modifier.matchParentSize()
                        )
                    }
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
                                    contentDescription = "Download",
                                    modifier = Modifier.size(48.dp),
                                    tint = Color.White.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }


                if (content.path != null && isPlaying && !hasError) {
                    Box(modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Mute",
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
            } else if (content.path != null && !hasError) {

                CircularWavyProgressIndicator(
                    progress = { progress },
                    color = Color.White,
                    modifier = Modifier.matchParentSize(),
                    trackColor = Color.Transparent
                )
            }


            if (!isPlaying && content.path != null && !hasError) {
                Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
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
                            contentDescription = "Edited",
                            modifier = Modifier.size(12.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = formatTime(msg.date),
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
