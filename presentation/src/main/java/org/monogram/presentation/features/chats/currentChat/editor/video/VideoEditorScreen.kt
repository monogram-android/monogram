package org.monogram.presentation.features.chats.currentChat.editor.video

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.presentation.R
import org.monogram.presentation.core.util.getMimeType
import org.monogram.presentation.features.chats.currentChat.components.VideoGLTextureView
import org.monogram.presentation.features.chats.currentChat.editor.photo.components.EditorTopBar
import org.monogram.presentation.features.chats.currentChat.editor.photo.components.TextEntryDialog
import org.monogram.presentation.features.chats.currentChat.editor.video.components.VideoCompressionControls
import org.monogram.presentation.features.chats.currentChat.editor.video.components.VideoFilterControls
import org.monogram.presentation.features.chats.currentChat.editor.video.components.VideoTextControls
import org.monogram.presentation.features.chats.currentChat.editor.video.components.VideoTrimControls
import java.io.File
import java.io.FileNotFoundException

enum class VideoEditorTool(@StringRes val labelRes: Int, val icon: ImageVector) {
    NONE(R.string.video_tool_view, Icons.Rounded.Visibility),
    TRIM(R.string.video_tool_trim, Icons.Rounded.ContentCut),
    FILTER(R.string.video_tool_filters, Icons.Rounded.AutoAwesome),
    TEXT(R.string.video_tool_text, Icons.Rounded.TextFields),
    COMPRESS(R.string.video_tool_compress, Icons.Rounded.Compress)
}

@OptIn(UnstableApi::class)
@Composable
fun VideoEditorScreen(
    videoPath: String,
    onClose: () -> Unit,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exoPlayer = remember {
        if (!File(videoPath).exists()) {
            Toast.makeText(context, context.getString(R.string.video_error_file_not_found), Toast.LENGTH_SHORT).show()
            onClose()
        }

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
            .build().apply {
                val mediaItem = MediaItem.Builder()
                    .setUri(videoPath)
                    .setMimeType(getMimeType(videoPath) ?: MimeTypes.VIDEO_MP4)
                    .build()
                setMediaItem(mediaItem)
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    var currentTool by remember { mutableStateOf(VideoEditorTool.NONE) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoDuration by remember { mutableLongStateOf(0L) }
    var currentPosition by remember { mutableLongStateOf(0L) }

    var trimRange by remember { mutableStateOf(VideoTrimRange()) }
    var selectedFilter by remember { mutableStateOf<VideoFilter?>(null) }
    val textElements = remember { mutableStateListOf<VideoTextElement>() }
    var videoQuality by remember { mutableStateOf(VideoQuality.ORIGINAL) }
    var isMuted by remember { mutableStateOf(false) }

    var showTextDialog by remember { mutableStateOf(false) }
    var editingTextElement by remember { mutableStateOf<VideoTextElement?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var glView by remember { mutableStateOf<VideoGLTextureView?>(null) }

    val hasChanges by remember {
        derivedStateOf {
            textElements.isNotEmpty() ||
                    selectedFilter != null ||
                    trimRange.startMs != 0L ||
                    (videoDuration > 0 && trimRange.endMs != videoDuration) ||
                    videoQuality != VideoQuality.ORIGINAL ||
                    isMuted
        }
    }

    val handleBack = {
        if (currentTool != VideoEditorTool.NONE) {
            currentTool = VideoEditorTool.NONE
        } else if (hasChanges) {
            showDiscardDialog = true
        } else {
            onClose()
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    videoDuration = exoPlayer.duration
                    if (trimRange.endMs == 0L) {
                        trimRange = trimRange.copy(endMs = videoDuration)
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                glView?.setVideoSize(videoSize.width, videoSize.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.cause is FileNotFoundException) {
                    Toast.makeText(context, context.getString(R.string.video_error_file_missing), Toast.LENGTH_SHORT).show()
                    onClose()
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
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition
            if (currentPosition >= trimRange.endMs) {
                exoPlayer.seekTo(trimRange.startMs)
            }
            delay(16)
        }
    }

    LaunchedEffect(selectedFilter) {
        glView?.setFilter(selectedFilter?.colorMatrix?.values)
    }

    BackHandler(onBack = handleBack)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            EditorTopBar(
                onClose = handleBack,
                onSave = {
                    if (!isSaving) {
                        scope.launch {
                            isSaving = true
                            val newPath = processVideo(
                                context = context,
                                inputPath = videoPath,
                                trimRange = trimRange,
                                filter = selectedFilter,
                                textElements = textElements,
                                quality = videoQuality,
                                muteAudio = isMuted
                            )
                            isSaving = false
                            onSave(newPath)
                        }
                    }
                },
                onUndo = {},
                onRedo = {},
                canUndo = false,
                canRedo = false
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    AnimatedContent(
                        targetState = currentTool,
                        label = "VideoToolOptions",
                        modifier = Modifier.fillMaxWidth()
                    ) { tool ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when (tool) {
                                VideoEditorTool.TRIM -> {
                                    Column(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        VideoTrimControls(
                                            duration = videoDuration,
                                            trimRange = trimRange,
                                            currentPosition = currentPosition,
                                            onTrimChange = {
                                                trimRange = it
                                                exoPlayer.seekTo(it.startMs)
                                            }
                                        )
                                        TextButton(onClick = { currentTool = VideoEditorTool.NONE }) {
                                            Text(stringResource(R.string.video_editor_apply))
                                        }
                                    }
                                }

                                VideoEditorTool.FILTER -> {
                                    Column(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        VideoFilterControls(
                                            selectedFilter = selectedFilter,
                                            onFilterSelect = { selectedFilter = it }
                                        )
                                        TextButton(onClick = { currentTool = VideoEditorTool.NONE }) {
                                            Text(stringResource(R.string.video_editor_apply))
                                        }
                                    }
                                }

                                VideoEditorTool.TEXT -> {
                                    Column(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        VideoTextControls(onAddText = {
                                            editingTextElement = null
                                            showTextDialog = true
                                        })
                                        TextButton(onClick = { currentTool = VideoEditorTool.NONE }) {
                                            Text(stringResource(R.string.video_editor_done))
                                        }
                                    }
                                }

                                VideoEditorTool.COMPRESS -> {
                                    Column(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        VideoCompressionControls(
                                            quality = videoQuality,
                                            onQualityChange = { videoQuality = it }
                                        )
                                        TextButton(onClick = { currentTool = VideoEditorTool.NONE }) {
                                            Text(stringResource(R.string.video_editor_apply))
                                        }
                                    }
                                }

                                else -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    ) {
                                        FilledIconButton(
                                            onClick = {
                                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                                            },
                                            modifier = Modifier.size(56.dp),
                                            colors = IconButtonDefaults.filledIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        ) {
                                            Icon(
                                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(24.dp))

                                        FilledTonalIconButton(
                                            onClick = { isMuted = !isMuted },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                                contentDescription = if (isMuted) stringResource(R.string.video_action_unmute) else stringResource(R.string.video_action_mute),
                                                tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
                        VideoEditorTool.entries.forEach { tool ->
                            NavigationBarItem(
                                selected = currentTool == tool,
                                onClick = { currentTool = tool },
                                icon = { Icon(tool.icon, contentDescription = stringResource(tool.labelRes)) },
                                label = { Text(stringResource(tool.labelRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .onGloballyPositioned { canvasSize = it.size },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoGLTextureView(ctx).apply {
                        configure(useAlpha = false, removeBlackBg = false)
                        onSurfaceReady = { surface ->
                            exoPlayer.setVideoSurface(surface)
                        }
                        glView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            textElements.forEach { element ->
                if (currentPosition >= element.startTimeMs && (element.endTimeMs == -1L || currentPosition <= element.endTimeMs)) {
                    val density = LocalDensity.current
                    var currentScale by remember(element.id) { mutableFloatStateOf(element.scale) }
                    var currentRotation by remember(element.id) { mutableFloatStateOf(element.rotation) }
                    var currentX by remember(element.id) { mutableFloatStateOf(element.positionX) }
                    var currentY by remember(element.id) { mutableFloatStateOf(element.positionY) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(currentTool, element.id) {
                                if (currentTool == VideoEditorTool.TEXT || currentTool == VideoEditorTool.NONE) {
                                    detectTransformGestures { _, pan, zoom, rotation ->
                                        currentX += pan.x / canvasSize.width
                                        currentY += pan.y / canvasSize.height
                                        currentScale *= zoom
                                        currentRotation += rotation
                                    }
                                }
                            }
                            .pointerInput(currentTool, element.id) {
                                if (currentTool == VideoEditorTool.TEXT || currentTool == VideoEditorTool.NONE) {
                                    detectTapGestures(onTap = {
                                        if (currentTool == VideoEditorTool.TEXT) {
                                            editingTextElement = element
                                            showTextDialog = true
                                        }
                                    })
                                }
                            }
                    ) {
                        LaunchedEffect(currentX, currentY, currentScale, currentRotation) {
                            val idx = textElements.indexOfFirst { it.id == element.id }
                            if (idx != -1) {
                                textElements[idx] = textElements[idx].copy(
                                    positionX = currentX,
                                    positionY = currentY,
                                    scale = currentScale,
                                    rotation = currentRotation
                                )
                            }
                        }

                        Text(
                            text = element.text,
                            color = element.color,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                shadow = Shadow(Color.Black, blurRadius = 8f)
                            ),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(
                                    x = with(density) { (currentX * canvasSize.width).toDp() },
                                    y = with(density) { (currentY * canvasSize.height).toDp() }
                                )
                                .graphicsLayer(
                                    scaleX = currentScale,
                                    scaleY = currentScale,
                                    rotationZ = currentRotation
                                )
                                .then(
                                    if (currentTool == VideoEditorTool.TEXT) {
                                        Modifier
                                            .border(1.dp, Color.White.copy(0.5f), RoundedCornerShape(4.dp))
                                            .padding(4.dp)
                                    } else Modifier
                                )
                        )
                    }
                }
            }

            if (isSaving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.video_discard_title)) },
            text = { Text(stringResource(R.string.video_discard_text)) },
            confirmButton = {
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.video_discard_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.video_editor_cancel))
                }
            }
        )
    }

    if (showTextDialog) {
        TextEntryDialog(
            initialText = editingTextElement?.text ?: "",
            initialColor = editingTextElement?.color ?: Color.White,
            onDismiss = {
                showTextDialog = false
                editingTextElement = null
            },
            onConfirm = { text, color ->
                if (editingTextElement != null) {
                    val index = textElements.indexOfFirst { it.id == editingTextElement!!.id }
                    if (index != -1) {
                        textElements[index] = textElements[index].copy(text = text, color = color)
                    }
                } else {
                    textElements.add(
                        VideoTextElement(
                            text = text,
                            color = color,
                            positionX = 0.4f,
                            positionY = 0.4f
                        )
                    )
                }
                showTextDialog = false
                editingTextElement = null
            },
            onDelete = {
                editingTextElement?.let { el -> textElements.removeIf { it.id == el.id } }
                showTextDialog = false
                editingTextElement = null
            }
        )
    }
}
