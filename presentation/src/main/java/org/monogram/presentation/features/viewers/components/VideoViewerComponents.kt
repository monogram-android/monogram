@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.viewers.components

import android.os.Build
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.monogram.domain.repository.PlayerDataSourceFactory
import org.monogram.domain.repository.StreamingRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.getMimeType
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.stickers.ui.menu.MenuToggleRow
import kotlin.math.max

private const val TAG = "VideoPage"

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VideoPage(
    path: String,
    fileId: Int,
    caption: String?,
    supportsStreaming: Boolean,
    downloadUtils: IDownloadUtils,
    onDismiss: () -> Unit,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onForward: (String) -> Unit,
    onDelete: ((String) -> Unit)?,
    onCopyLink: ((String) -> Unit)?,
    onCopyText: ((String) -> Unit)?,
    onSaveGif: ((String) -> Unit)?,
    showSettingsMenu: Boolean,
    onToggleSettings: () -> Unit,
    isGesturesEnabled: Boolean,
    isDoubleTapSeekEnabled: Boolean,
    seekDuration: Int,
    isZoomEnabled: Boolean,
    isActive: Boolean,
    onCurrentVideoPipModeChanged: (Boolean) -> Unit,
    zoomState: ZoomState,
    rootState: DismissRootState,
    screenHeightPx: Float,
    dismissDistancePx: Float,
    dismissVelocityThreshold: Float
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val streamingRepository = koinInject<StreamingRepository>()
    val playerFactory = koinInject<PlayerDataSourceFactory>()
    val seekDurationMs = seekDuration * 1000L

    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnToggleControls by rememberUpdatedState(onToggleControls)
    val currentOnToggleSettings by rememberUpdatedState(onToggleSettings)

    var isInPipMode by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var isEnded by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var isLocked by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var isMuted by remember { mutableStateOf(false) }

    var gestureIcon by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText by remember { mutableStateOf<String?>(null) }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var forwardSeekFeedback by remember { mutableStateOf(false) }
    var rewindSeekFeedback by remember { mutableStateOf(false) }

    val pipId = remember(fileId, path) { if (fileId != 0) fileId else path.hashCode() }

    val downloadProgress by if (fileId != 0) {
        streamingRepository.getDownloadProgress(fileId).collectAsState(initial = 0f)
    } else {
        remember { mutableStateOf(1f) }
    }

    val exoPlayer = remember(path, fileId, supportsStreaming) {
        Log.d(TAG, "Creating ExoPlayer for path=$path, fileId=$fileId")
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val playerBuilder = ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, true)

        val dataSourceFactory = if (supportsStreaming && fileId != 0) {
            playerFactory.createPayload(fileId) as DataSource.Factory
        } else {
            null
        }

        if (dataSourceFactory != null) {
            playerBuilder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
        } else {
            playerBuilder.setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
        }

        playerBuilder.build().apply {
            val mediaItem = MediaItem.Builder()
                .setUri(path)
                .apply {
                    getMimeType(path)?.let { setMimeType(it) }
                }
                .build()
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = isActive
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val activity = context.findActivity()
        val observer = LifecycleEventObserver { _, event ->
            val isPip = activity?.isInPictureInPictureMode == true
            if (event == Lifecycle.Event.ON_PAUSE && !isPip) exoPlayer.pause()
            if (event == Lifecycle.Event.ON_STOP && !isPip) exoPlayer.pause()
        }

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
                isPlaying = isPlayingChanged
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                isEnded = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.message}", error)
                isBuffering = false
                if (!showControls) currentOnToggleControls()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = if ((videoSize.unappliedRotationDegrees / 90) % 2 == 1)
                        videoSize.height.toFloat() / videoSize.width.toFloat()
                    else videoSize.width.toFloat() / videoSize.height.toFloat()
                    if (ratio != videoAspectRatio) {
                        videoAspectRatio = ratio
                    }
                }
            }
        }

        exoPlayer.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            Log.d(TAG, "Disposing ExoPlayer for $path")
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, isActive, isInPipMode) {
        if (isActive || isInPipMode) exoPlayer.play() else exoPlayer.pause()
    }

    LaunchedEffect(isInPipMode, isActive) {
        onCurrentVideoPipModeChanged(if (isActive) isInPipMode else false)
    }

    LaunchedEffect(isEnded) {
        if (isEnded && !showControls) {
            currentOnToggleControls()
        }
    }

    LaunchedEffect(isPlaying, showControls, showSettingsMenu) {
        if (isPlaying && showControls && !showSettingsMenu && !isLocked) {
            delay(3500)
            if (isPlaying && showControls && !showSettingsMenu && !isLocked) {
                currentOnToggleControls()
            }
        }
    }

    PipController(
        isPlaying = isPlaying,
        videoAspectRatio = videoAspectRatio,
        pipId = pipId,
        isActive = isActive,
        onPlay = {
            if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0)
            exoPlayer.play()
        },
        onPause = {
            exoPlayer.pause()
        },
        onRewind = {
            exoPlayer.seekTo(max(0, exoPlayer.currentPosition - seekDurationMs))
        },
        onForward = {
            exoPlayer.seekTo(exoPlayer.currentPosition + seekDurationMs)
        },
        onPipModeChanged = {
            isInPipMode = it
            if (!it && !isActive) exoPlayer.pause()
        }
    )

    LaunchedEffect(exoPlayer) {
        while (true) {
            val pos = exoPlayer.currentPosition
            if (currentPosition != pos) {
                currentPosition = pos
            }
            if (totalDuration <= 0L) {
                totalDuration = exoPlayer.duration.coerceAtLeast(0L)
            }
            delay(200)
        }
    }

    LaunchedEffect(exoPlayer, playbackSpeed) { exoPlayer.setPlaybackSpeed(playbackSpeed) }
    LaunchedEffect(exoPlayer, repeatMode) { exoPlayer.repeatMode = repeatMode }
    LaunchedEffect(exoPlayer, isMuted) { exoPlayer.volume = if (isMuted) 0f else 1f }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .videoGestures(
                exoPlayer = exoPlayer,
                isLocked = isLocked,
                isInPipMode = isInPipMode,
                isDoubleTapSeekEnabled = isDoubleTapSeekEnabled,
                isGesturesEnabled = isGesturesEnabled,
                isZoomEnabled = isZoomEnabled,
                seekDurationMs = seekDurationMs,
                zoomState = zoomState,
                rootState = rootState,
                screenHeightPx = screenHeightPx,
                dismissDistancePx = dismissDistancePx,
                dismissVelocityThreshold = dismissVelocityThreshold,
                onDismiss = currentOnDismiss,
                onToggleControls = currentOnToggleControls,
                onGestureOverlayChange = { visible, icon, text ->
                    showGestureOverlay = visible
                    gestureIcon = icon
                    gestureText = text
                },
                onSeekFeedback = { isRewind, _ ->
                    if (isRewind) rewindSeekFeedback = true else forwardSeekFeedback = true
                },
                context = context
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (!isInPipMode) {
                        scaleX = zoomState.scale.value
                        scaleY = zoomState.scale.value
                        translationX = zoomState.offsetX.value
                        translationY = zoomState.offsetY.value
                    }
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        setResizeMode(if (isInPipMode) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeMode)
                        setOnClickListener { currentOnToggleControls() }
                        playerView = this
                    }
                },
                update = { view ->
                    view.player = exoPlayer
                    view.resizeMode = if (isInPipMode) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeMode
                    view.setOnClickListener { currentOnToggleControls() }
                    playerView = view
                },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (!isInPipMode) {
            if (isBuffering) LoadingIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )

            SeekFeedback(
                rewindSeekFeedback,
                true,
                seekDuration,
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 64.dp)
            )
            SeekFeedback(
                forwardSeekFeedback,
                false,
                seekDuration,
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 64.dp)
            )
            GestureOverlay(showGestureOverlay, gestureIcon, gestureText, Modifier.align(Alignment.Center))

            if (isLocked) {
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        IconButton(
                            onClick = {
                                isLocked = false
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(16.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                        ) { Icon(Icons.Rounded.Lock, stringResource(R.string.action_unlock), tint = MaterialTheme.colorScheme.onSurface) }
                    }
                }
            } else {
                VideoPlayerControls(
                    visible = showControls,
                    isPlaying = isPlaying,
                    isEnded = isEnded,
                    currentPosition = currentPosition,
                    totalDuration = totalDuration,
                    currentTime = currentTime(),
                    isSettingsOpen = showSettingsMenu,
                    caption = caption,
                    downloadProgress = downloadProgress,
                    onPlayPauseToggle = {
                        if (isEnded) {
                            exoPlayer.seekTo(0); exoPlayer.play()
                        } else {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }
                    },
                    onSeek = {
                        exoPlayer.seekTo(it)
                    },
                    onBack = currentOnDismiss,
                    onForward = {
                        exoPlayer.seekTo(exoPlayer.currentPosition + seekDurationMs)
                    },
                    onRewind = {
                        exoPlayer.seekTo(max(0, exoPlayer.currentPosition - seekDurationMs))
                    },
                    onSettingsToggle = currentOnToggleSettings,
                    onLockToggle = {
                        isLocked = true
                        if (showSettingsMenu) {
                            currentOnToggleSettings()
                        }
                    }
                )

                AnimatedVisibility(
                    visible = showSettingsMenu && showControls,
                    enter = fadeIn(tween(150)) + scaleIn(
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
                        initialScale = 0.8f, transformOrigin = TransformOrigin(1f, 0f)
                    ),
                    exit = fadeOut(tween(150)) + scaleOut(
                        animationSpec = tween(150), targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 56.dp, end = 16.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                        VideoSettingsMenu(
                            playbackSpeed = playbackSpeed,
                            repeatMode = repeatMode,
                            resizeMode = resizeMode,
                            isMuted = isMuted,
                            isZoomEnabled = isZoomEnabled,
                            onSpeedSelected = {
                                playbackSpeed = it
                            },
                            onRepeatToggle = {
                                repeatMode =
                                    if (repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                            },
                            onResizeToggle = {
                                resizeMode =
                                    if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                            },
                            onMuteToggle = {
                                isMuted = !isMuted
                            },
                            onRotationToggle = {
                                val activity = context.findActivity()
                                activity?.requestedOrientation =
                                    if (activity?.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            },
                            onEnterPip = {
                                currentOnToggleSettings()
                                enterPipMode(context, isPlaying, videoAspectRatio, pipId)
                            },
                            onDownload = {
                                downloadUtils.saveFileToDownloads(path); currentOnToggleSettings()
                            },
                            onCopyLink = { onCopyLink?.invoke(path); currentOnToggleSettings() },
                            onCopyText = if (!caption.isNullOrBlank()) {
                                { onCopyText?.invoke(path); currentOnToggleSettings() }
                            } else null,
                            onForward = { onForward(path); currentOnToggleSettings() },
                            onDelete = onDelete?.let { { it(path); currentOnToggleSettings() } },
                            onScreenshot = { toClipboard ->
                                val view = playerView ?: return@VideoSettingsMenu
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    val surfaceView =
                                        view.videoSurfaceView as? android.view.SurfaceView ?: return@VideoSettingsMenu
                                    val bitmap =
                                        androidx.core.graphics.createBitmap(surfaceView.width, surfaceView.height)
                                    android.view.PixelCopy.request(surfaceView, bitmap, { result ->
                                        if (result == android.view.PixelCopy.SUCCESS) {
                                            if (toClipboard) downloadUtils.copyBitmapToClipboard(bitmap) else downloadUtils.saveBitmapToGallery(
                                                bitmap
                                            )
                                        }
                                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                                }
                                currentOnToggleSettings()
                            },
                            onSaveGif = onSaveGif?.let { { it(path); currentOnToggleSettings() } }
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(rewindSeekFeedback) {
        if (rewindSeekFeedback) {
            delay(600)
            rewindSeekFeedback = false
        }
    }

    LaunchedEffect(forwardSeekFeedback) {
        if (forwardSeekFeedback) {
            delay(600)
            forwardSeekFeedback = false
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VideoPlayerControls(
    visible: Boolean,
    isPlaying: Boolean,
    isEnded: Boolean,
    currentPosition: Long,
    totalDuration: Long,
    currentTime: String,
    isSettingsOpen: Boolean,
    caption: String? = null,
    downloadProgress: Float = 0f,
    onPlayPauseToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRewind: () -> Unit,
    onSettingsToggle: () -> Unit,
    onLockToggle: () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentPosition, totalDuration, isDragging) {
        if (!isDragging && totalDuration > 0) {
            val newPos = currentPosition.toFloat() / totalDuration.toFloat()
            if (sliderPosition != newPos) {
                sliderPosition = newPos
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ViewerTopBar(
                onBack = onBack,
                onActionClick = onSettingsToggle,
                isActionActive = isSettingsOpen,
                onLockClick = onLockToggle
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRewind, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Replay10, stringResource(R.string.viewer_seek_rewind_cd), tint = Color.White, modifier = Modifier.fillMaxSize())
                }

                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(targetValue = if (isPressed) 0.9f else 1f, label = "scale")

                Box(
                    modifier = Modifier
                        .scale(scale)
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f))
                        .clickable(interactionSource = interactionSource, indication = null) { onPlayPauseToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isEnded -> Icons.Rounded.Replay
                            isPlaying -> Icons.Rounded.Pause
                            else -> Icons.Rounded.PlayArrow
                        },
                        contentDescription = when {
                            isEnded -> stringResource(R.string.action_restart)
                            isPlaying -> stringResource(R.string.action_pause)
                            else -> stringResource(R.string.action_play)
                        },
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(48.dp)
                    )
                }

                IconButton(onClick = onForward, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Forward10, stringResource(R.string.viewer_seek_forward_cd), tint = Color.White, modifier = Modifier.fillMaxSize())
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.5f),
                                Color.Black.copy(alpha = 0.9f)
                            )
                        )
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp)
                    .padding(top = 32.dp, bottom = 24.dp)
            ) {
                if (!caption.isNullOrBlank()) {
                    ViewerCaption(caption = caption, showGradient = false)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${formatDuration(if (isDragging) (sliderPosition * totalDuration).toLong() else currentPosition)} / ${
                            formatDuration(
                                totalDuration
                            )
                        }",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = currentTime,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (downloadProgress < 1f) {
                        LinearWavyProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .padding(horizontal = 2.dp),
                            color = Color.White.copy(alpha = 0.2f),
                            trackColor = Color.Transparent
                        )
                    }
                    Slider(
                        value = sliderPosition,
                        onValueChange = {
                            isDragging = true
                            sliderPosition = it
                        },
                        onValueChangeFinished = {
                            isDragging = false
                            onSeek((sliderPosition * totalDuration).toLong())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private enum class SettingsScreen { MAIN, SPEED, SCREENSHOT }

@OptIn(UnstableApi::class)
@Composable
fun VideoSettingsMenu(
    playbackSpeed: Float,
    repeatMode: Int,
    resizeMode: Int,
    isMuted: Boolean,
    isZoomEnabled: Boolean,
    onSpeedSelected: (Float) -> Unit,
    onRepeatToggle: () -> Unit,
    onResizeToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onRotationToggle: () -> Unit,
    onEnterPip: () -> Unit,
    onDownload: () -> Unit,
    onScreenshot: (Boolean) -> Unit,
    onCopyLink: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
    onForward: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    onSaveGif: (() -> Unit)? = null
) {
    var currentScreen by remember { mutableStateOf(SettingsScreen.MAIN) }

    ViewerSettingsDropdown {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState != SettingsScreen.MAIN) {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith slideOutHorizontally { width -> -width } + fadeOut()
                } else {
                    slideInHorizontally { width -> -width } + fadeIn() togetherWith slideOutHorizontally { width -> width } + fadeOut()
                }
            },
            label = "SettingsNavigation"
        ) { screen ->
            when (screen) {
                SettingsScreen.MAIN -> {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        MenuOptionRow(
                            icon = Icons.Rounded.Speed,
                            title = stringResource(R.string.settings_playback_speed),
                            value = "${playbackSpeed}x",
                            onClick = { currentScreen = SettingsScreen.SPEED },
                            trailingIcon = Icons.AutoMirrored.Rounded.KeyboardArrowRight
                        )
                        if (isZoomEnabled) {
                            MenuOptionRow(
                                icon = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) Icons.Rounded.AspectRatio else Icons.Rounded.FitScreen,
                                title = stringResource(R.string.settings_scale_mode),
                                value = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) stringResource(R.string.settings_scale_fit) else stringResource(R.string.settings_scale_zoom),
                                onClick = onResizeToggle
                            )
                        }
                        MenuOptionRow(
                            icon = Icons.Rounded.ScreenRotation,
                            title = stringResource(R.string.settings_rotate_screen),
                            onClick = onRotationToggle
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            MenuOptionRow(
                                icon = Icons.Rounded.PictureInPicture,
                                title = stringResource(R.string.settings_pip),
                                onClick = onEnterPip
                            )
                            MenuOptionRow(
                                icon = Icons.Rounded.Camera,
                                title = stringResource(R.string.settings_screenshot),
                                onClick = { currentScreen = SettingsScreen.SCREENSHOT },
                                trailingIcon = Icons.AutoMirrored.Rounded.KeyboardArrowRight
                            )
                        }
                        MenuToggleRow(
                            icon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            title = stringResource(R.string.settings_loop_video),
                            isChecked = repeatMode == Player.REPEAT_MODE_ONE,
                            onCheckedChange = { onRepeatToggle() })
                        MenuToggleRow(
                            icon = if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            title = stringResource(R.string.settings_mute_audio),
                            isChecked = isMuted,
                            onCheckedChange = { onMuteToggle() })
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )
                        MenuOptionRow(icon = Icons.Rounded.Download, title = stringResource(R.string.action_download_video), onClick = onDownload)
                        if (onSaveGif != null) MenuOptionRow(
                            icon = Icons.Rounded.Gif,
                            title = stringResource(R.string.action_save_gifs),
                            onClick = onSaveGif
                        )
                        if (onCopyText != null) MenuOptionRow(
                            icon = Icons.Rounded.ContentCopy,
                            title = stringResource(R.string.action_copy_text),
                            onClick = onCopyText
                        )
                        if (onCopyLink != null) MenuOptionRow(
                            icon = Icons.Rounded.Link,
                            title = stringResource(R.string.action_copy_link),
                            onClick = onCopyLink
                        )
                        MenuOptionRow(icon = Icons.AutoMirrored.Rounded.Forward, title = stringResource(R.string.action_forward), onClick = onForward)
                        if (onDelete != null) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                            MenuOptionRow(
                                icon = Icons.Rounded.Delete,
                                title = stringResource(R.string.action_delete),
                                onClick = onDelete,
                                iconTint = MaterialTheme.colorScheme.error,
                                textColor = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                SettingsScreen.SPEED -> {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentScreen = SettingsScreen.MAIN }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                stringResource(R.string.viewer_back),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.settings_playback_speed),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            SpeedSelectionRow(
                                speed = speed,
                                isSelected = playbackSpeed == speed,
                                onClick = { onSpeedSelected(speed) })
                        }
                    }
                }
                SettingsScreen.SCREENSHOT -> {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentScreen = SettingsScreen.MAIN }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                stringResource(R.string.viewer_back),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.settings_screenshot),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        MenuOptionRow(
                            icon = Icons.Rounded.Download,
                            title = stringResource(R.string.action_save_gallery),
                            onClick = { onScreenshot(false); currentScreen = SettingsScreen.MAIN })
                        MenuOptionRow(
                            icon = Icons.Rounded.ContentPaste,
                            title = stringResource(R.string.action_copy_clipboard),
                            onClick = { onScreenshot(true); currentScreen = SettingsScreen.MAIN })
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedSelectionRow(speed: Float, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (speed == 1f) stringResource(R.string.settings_speed_normal) else "${speed}x",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        if (isSelected) Icon(
            Icons.Rounded.Check,
            "Selected",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}
