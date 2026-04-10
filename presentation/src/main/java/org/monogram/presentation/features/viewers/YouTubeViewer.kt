@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.viewers

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.features.viewers.components.*
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.math.max

@Composable
fun YouTubeViewer(
    videoUrl: String,
    onDismiss: () -> Unit,
    caption: String? = null,
    onForward: (String) -> Unit = {},
    onCopyLink: ((String) -> Unit)? = null,
    onCopyText: ((String) -> Unit)? = null,
    isPipEnabled: Boolean = true,
    isActive: Boolean = true
) {
    val youtubeId = extractYouTubeId(videoUrl) ?: return
    val startTime = extractYouTubeTime(videoUrl)
    val context = LocalContext.current
    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()
    val lifecycleOwner = LocalLifecycleOwner.current
    val playerState = remember { YouTubePlayerState() }
    var isInPipMode by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var currentTimeStr by remember { mutableStateOf("") }

    val scale = remember { Animatable(0.8f) }
    val alpha = remember { Animatable(0f) }

    var gestureIcon by remember { mutableStateOf<androidx.compose.ui.graphics.vector.ImageVector?>(null) }
    var gestureText by remember { mutableStateOf<String?>(null) }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var forwardSeekFeedback by remember { mutableStateOf(false) }
    var rewindSeekFeedback by remember { mutableStateOf(false) }

    var isLongPressing by remember { mutableStateOf(false) }
    val originalSpeed = remember { mutableFloatStateOf(1f) }
    val viewConfiguration = LocalViewConfiguration.current
    val coroutineScope = rememberCoroutineScope()

    val pipId = remember(videoUrl) { videoUrl.hashCode() }

    val adDomains = remember {
        listOf(
            "googleads", "doubleclick.net", "googlesyndication", "adservice.google",
            "pagead", "spotxchange", "smartadserver"
        )
    }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.5632.145 Safari/537.36"
                setSupportMultipleWindows(true)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url.toString().lowercase()
                    val isAd = adDomains.any { url.contains(it) }

                    if (isAd) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            addJavascriptInterface(
                YoutubeProxy(
                    state = playerState,
                    onLoaded = { playerState.isLoading = false }
                ), "YoutubeProxy"
            )
            webChromeClient = WebChromeClient()
            setBackgroundColor(0)
        }
    }

    fun exitPipMode() {
        val activity = context.findActivity() ?: return
        if (activity.isInPictureInPictureMode) {
            val intent = Intent(context, activity.javaClass).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            webView.evaluateJavascript("playVideo()", null)
        } else {
            webView.evaluateJavascript("pauseVideo()", null)
        }
    }

    PipController(
        isPlaying = playerState.isPlaying,
        videoAspectRatio = 16f / 9f,
        pipId = pipId,
        isActive = isActive,
        onPlay = {
            webView.visibility = View.VISIBLE
            webView.onResume()
            webView.resumeTimers()
            webView.evaluateJavascript("playVideo()", null)
            playerState.isPlaying = true
        },
        onPause = {
            webView.evaluateJavascript("pauseVideo()", null)
            playerState.isPlaying = false
        },
        onRewind = {
            val newTime = max(0f, playerState.currentTime - 10)
            playerState.currentTime = newTime
            webView.evaluateJavascript("seekTo($newTime, true)", null)
        },
        onForward = {
            val newTime = playerState.currentTime + 10
            playerState.currentTime = newTime
            webView.evaluateJavascript("seekTo($newTime, true)", null)
        },
        onPipModeChanged = { isInPipMode = it }
    )

    LaunchedEffect(youtubeId) {
        webView.loadDataWithBaseURL(
            "https://www.youtube-nocookie.com",
            getYouTubeHtml(youtubeId, startTime),
            "text/html",
            "utf-8",
            null
        )
    }

    DisposableEffect(lifecycleOwner) {
        val activity = context.findActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val observer = LifecycleEventObserver { _, event ->
            val isPip = activity?.isInPictureInPictureMode == true

            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (!isPip) {
                        webView.evaluateJavascript("pauseVideo()", null)
                        playerState.isPlaying = false
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    if (!isPip) {
                        webView.evaluateJavascript("pauseVideo()", null)
                        playerState.isPlaying = false
                        webView.onPause()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume()
                    webView.resumeTimers()
                    webView.evaluateJavascript("hideOverlays(); checkAds();", null)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exitPipMode()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    activity?.setPictureInPictureParams(
                        PictureInPictureParams.Builder()
                            .setAutoEnterEnabled(false)
                            .build()
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.let {
                WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                    show(WindowInsetsCompat.Type.systemBars())
                }
            }
            webView.stopLoading()
            webView.onPause()
            webView.destroy()
        }
    }

    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) }
        launch { alpha.animateTo(1f, tween(150)) }
    }

    LaunchedEffect(showControls, isInPipMode) {
        if (!showControls) showSettingsMenu = false

        val activity = context.findActivity()
        activity?.let {
            val window = it.window
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            if (showControls && !isInPipMode) {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(showControls, playerState.isPlaying, showSettingsMenu, isInPipMode) {
        if (showControls && playerState.isPlaying && !showSettingsMenu && !isInPipMode) {
            delay(4000)
            showControls = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimeStr = SimpleDateFormat(timeFormat, Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    LaunchedEffect(playerState.playbackSpeed) {
        webView.evaluateJavascript("setPlaybackRate(${playerState.playbackSpeed})", null)
    }

    LaunchedEffect(playerState.isMuted) {
        if (playerState.isMuted) webView.evaluateJavascript("mute()", null)
        else webView.evaluateJavascript("unMute()", null)
    }

    LaunchedEffect(playerState.isLooping) {
        webView.evaluateJavascript("setLoop(${playerState.isLooping})", null)
    }

    LaunchedEffect(playerState.isCaptionsEnabled) {
        webView.evaluateJavascript("setCaptionsEnabled(${playerState.isCaptionsEnabled})", null)
    }

    LaunchedEffect(forwardSeekFeedback) {
        if (forwardSeekFeedback) {
            delay(600); forwardSeekFeedback = false
        }
    }
    LaunchedEffect(rewindSeekFeedback) {
        if (rewindSeekFeedback) {
            delay(600); rewindSeekFeedback = false
        }
    }

    BackHandler {
        if (isInPipMode) exitPipMode()
        else if (showSettingsMenu) showSettingsMenu = false
        else onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (isInPipMode) 1f else alpha.value))
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .scale(if (isInPipMode) 1f else scale.value),
            update = { view ->
                if (isInPipMode) {
                    view.onResume()
                }
            }
        )

        if (!isInPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(playerState.isLocked) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                if (!playerState.isLocked) {
                                    val width = size.width
                                    if (offset.x < width / 2) {
                                        val newTime = max(0f, playerState.currentTime - 10)
                                        playerState.currentTime = newTime
                                        webView.evaluateJavascript("seekTo($newTime, true)", null)
                                        rewindSeekFeedback = true
                                    } else {
                                        val newTime = playerState.currentTime + 10
                                        playerState.currentTime = newTime
                                        webView.evaluateJavascript("seekTo($newTime, true)", null)
                                        forwardSeekFeedback = true
                                    }
                                }
                            },
                            onTap = {
                                if (showSettingsMenu) {
                                    showSettingsMenu = false
                                    showControls = false
                                } else {
                                    showControls = !showControls
                                }
                            },
                            onPress = {
                                val longPressJob = coroutineScope.launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    if (!playerState.isLocked && playerState.isPlaying) {
                                        originalSpeed.floatValue = playerState.playbackSpeed
                                        playerState.playbackSpeed = 2f
                                        isLongPressing = true
                                        gestureIcon = Icons.Rounded.Speed
                                        gestureText = "2x Speed"
                                        showGestureOverlay = true
                                        showControls = false
                                    }
                                }
                                try {
                                    awaitRelease()
                                } finally {
                                    longPressJob.cancel()
                                    if (isLongPressing) {
                                        playerState.playbackSpeed = originalSpeed.floatValue
                                        isLongPressing = false
                                        showGestureOverlay = false
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(playerState.isLocked) {
                        detectVerticalDragGestures(
                            onDragStart = { if (!playerState.isLocked) showGestureOverlay = true },
                            onDragEnd = {
                                if (isLongPressing) {
                                    playerState.playbackSpeed = originalSpeed.floatValue
                                    isLongPressing = false
                                }
                                showGestureOverlay = false
                            },
                            onDragCancel = {
                                if (isLongPressing) {
                                    playerState.playbackSpeed = originalSpeed.floatValue
                                    isLongPressing = false
                                }
                                showGestureOverlay = false
                            }
                        ) { change, dragAmount ->
                            if (!playerState.isLocked && !isLongPressing) {
                                val width = size.width
                                val isLeft = change.position.x < width / 2
                                val activity = context.findActivity()

                                if (isLeft && activity != null) {
                                    val lp = activity.window.attributes
                                    var newBrightness =
                                        (lp.screenBrightness.takeIf { it != -1f } ?: 0.5f) - (dragAmount / 1000f)
                                    newBrightness = newBrightness.coerceIn(0f, 1f)
                                    lp.screenBrightness = newBrightness
                                    activity.window.attributes = lp
                                    gestureIcon = Icons.Rounded.BrightnessMedium
                                    gestureText = "${(newBrightness * 100).toInt()}%"
                                } else {
                                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val delta = -(dragAmount / 50f)
                                    val newVol = (currentVol + delta).coerceIn(0f, maxVol.toFloat())
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol.toInt(), 0)
                                    gestureIcon = Icons.AutoMirrored.Rounded.VolumeUp
                                    gestureText = "${((newVol / maxVol) * 100).toInt()}%"
                                }
                            }
                        }
                    }
            ) {
                if (playerState.isLoading) {
                    LoadingIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                SeekFeedback(rewindSeekFeedback, true, 10, Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 64.dp))
                SeekFeedback(forwardSeekFeedback, false, 10, Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 64.dp))
                GestureOverlay(showGestureOverlay, gestureIcon, gestureText, Modifier.align(Alignment.Center))

                if (playerState.isLocked) {
                    AnimatedVisibility(
                        visible = showControls,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            IconButton(
                                onClick = { playerState.isLocked = false },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .padding(16.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                            ) {
                                Icon(Icons.Rounded.Lock, "Unlock", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                } else {
                    YouTubePlayerControlsUI(
                        visible = showControls,
                        isPlaying = playerState.isPlaying,
                        currentPosition = (playerState.currentTime * 1000).toLong(),
                        totalDuration = (playerState.duration * 1000).toLong(),
                        bufferedPosition = (playerState.bufferedAmount * playerState.duration * 1000).toLong(),
                        videoTitle = playerState.videoTitle,
                        currentTimeStr = currentTimeStr,
                        isSettingsOpen = showSettingsMenu,
                        caption = caption,
                        onPlayPauseToggle = {
                            if (playerState.isPlaying) {
                                webView.evaluateJavascript("pauseVideo()", null)
                                playerState.isPlaying = false
                            } else {
                                webView.evaluateJavascript("playVideo()", null)
                                playerState.isPlaying = true
                            }
                        },
                        onSeek = {
                            val newTime = it / 1000f
                            playerState.currentTime = newTime
                            webView.evaluateJavascript("seekTo($newTime, true)", null)
                        },
                        onBack = onDismiss,
                        onForward = {
                            val newTime = playerState.currentTime + 10
                            playerState.currentTime = newTime
                            webView.evaluateJavascript("seekTo($newTime, true)", null)
                        },
                        onRewind = {
                            val newTime = max(0f, playerState.currentTime - 10)
                            playerState.currentTime = newTime
                            webView.evaluateJavascript("seekTo($newTime, true)", null)
                        },
                        onSettingsToggle = { showSettingsMenu = !showSettingsMenu }
                    )

                    AnimatedVisibility(
                        visible = showSettingsMenu && showControls,
                        enter = fadeIn(tween(150)) + scaleIn(
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
                            initialScale = 0.8f,
                            transformOrigin = TransformOrigin(1f, 0f)
                        ),
                        exit = fadeOut(tween(150)) + scaleOut(
                            animationSpec = tween(150),
                            targetScale = 0.9f,
                            transformOrigin = TransformOrigin(1f, 0f)
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(top = 56.dp, end = 16.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                            YouTubeSettingsMenu(
                                playbackSpeed = playerState.playbackSpeed,
                                isMuted = playerState.isMuted,
                                isLooping = playerState.isLooping,
                                isCaptionsEnabled = playerState.isCaptionsEnabled,
                                availableQualities = playerState.availableQualities,
                                currentQuality = playerState.currentQuality,
                                onQualitySelected = {
                                    playerState.currentQuality = it
                                    webView.evaluateJavascript("setPlaybackQuality('$it')", null)
                                },
                                onSpeedSelected = { playerState.playbackSpeed = it },
                                onMuteToggle = { playerState.isMuted = !playerState.isMuted },
                                onLoopToggle = { playerState.isLooping = !playerState.isLooping },
                                onCaptionsToggle = { playerState.isCaptionsEnabled = !playerState.isCaptionsEnabled },
                                onLockToggle = { playerState.isLocked = true; showSettingsMenu = false },
                                onRotationToggle = {
                                    val activity = context.findActivity()
                                    activity?.requestedOrientation =
                                        if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                },
                                onCopyLink = { onCopyLink?.invoke(videoUrl); showSettingsMenu = false },
                                onCopyLinkWithTime = {
                                    val time = playerState.currentTime.toInt()
                                    val baseVideoUrl = videoUrl
                                        .replace(Regex("([?&])t=[^&]*&"), "$1")
                                        .replace(Regex("[?&]t=[^&]*$"), "")
                                    val urlWithTime =
                                        if (baseVideoUrl.contains("?")) "$baseVideoUrl&t=${time}s" else "$baseVideoUrl?t=${time}s"
                                    onCopyLink?.invoke(urlWithTime)
                                    showSettingsMenu = false
                                },
                                onEnterPip = {
                                    if (isPipEnabled) {
                                        showSettingsMenu = false
                                        showControls = false
                                        enterPipMode(context, playerState.isPlaying, 16f / 9f, pipId)
                                    }
                                },
                                onCopyText = if (!caption.isNullOrBlank()) {
                                    { onCopyText?.invoke(caption); showSettingsMenu = false }
                                } else null,
                                onForward = { onForward(videoUrl); showSettingsMenu = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

class YouTubePlayerState {
    var isPlaying by mutableStateOf(false)
    var currentTime by mutableStateOf(0f)
    var duration by mutableStateOf(0f)
    var isLoading by mutableStateOf(true)
    var playbackSpeed by mutableFloatStateOf(1f)
    var isMuted by mutableStateOf(false)
    var isLocked by mutableStateOf(false)
    var videoTitle by mutableStateOf("")
    var bufferedAmount by mutableFloatStateOf(0f)
    var isLooping by mutableStateOf(false)
    var isCaptionsEnabled by mutableStateOf(false)
    var availableQualities by mutableStateOf<List<String>>(emptyList())
    var currentQuality by mutableStateOf("auto")
}

class YoutubeProxy(
    private val state: YouTubePlayerState,
    private val onLoaded: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onPlayerStateChange(playerState: Int) {
        handler.post {
            state.isPlaying = playerState == 1 || playerState == 3
        }
    }

    @JavascriptInterface
    fun onPlayerNotifyDuration(duration: Float) {
        handler.post { state.duration = duration }
    }

    @JavascriptInterface
    fun onPlayerNotifyCurrentPosition(position: Float) {
        handler.post { state.currentTime = position }
    }

    @JavascriptInterface
    fun onVideoTitle(title: String) {
        handler.post { state.videoTitle = title }
    }

    @JavascriptInterface
    fun onBufferedAmount(amount: Float) {
        handler.post { state.bufferedAmount = amount }
    }

    @JavascriptInterface
    fun onPlayerLoaded() {
        handler.post { onLoaded() }
    }

    @JavascriptInterface
    fun onPlayerError(error: Int) {
    }

    @JavascriptInterface
    fun onAvailableQualities(qualities: String) {
        handler.post { state.availableQualities = qualities.split(",").filter { it.isNotBlank() } }
    }

    @JavascriptInterface
    fun onQualityChange(quality: String) {
        handler.post { state.currentQuality = quality }
    }
}

fun getYouTubeHtml(videoId: String, startTime: Int = 0): String {
    val cssHide = """
        .ytp-chrome-top, .ytp-chrome-bottom, .ytp-gradient-top, .ytp-gradient-bottom, 
        .ytp-pause-overlay, .ytp-youtube-button, .ytp-show-cards-title, 
        .ytp-paid-content-overlay, .ytp-ce-element, .ytp-watermark, 
        .ytp-button.ytp-settings-button, .ytp-button.ytp-subtitles-button, 
        .ytp-button.ytp-fullscreen-button, .ytp-contextmenu, .ytp-error, .ytp-bezel,                
        .ytp-endscreen-content, .ytp-ce-covering-overlay, .ytp-ce-element-show,
        .ytp-ce-video, .ytp-ce-channel, .ytp-suggestion-set, .ytp-scroll-min,
        .ytp-ad-overlay-container, .ytp-ad-image-overlay, .ytp-ad-text-overlay, 
        .ytp-ad-module, .ytp-suggested-action, .ytp-cards-button, .ytp-cards-teaser, 
        .ytp-tooltip, .ytp-bezel-text, .ytp-miniplayer-ui, .ytp-title-channel,
        .annotation, .ytp-branding-logo, .ytp-branding-link,
        .video-ads, .ytp-ad-progress-list, .ytp-ad-player-overlay
        { display: none !important; opacity: 0 !important; pointer-events: none !important; width: 0 !important; height: 0 !important; }
    """.trimIndent()

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                body { margin: 0; width:100%; height:100%; background-color:#000; overflow: hidden; }
                html { width:100%; height:100%; background-color:#000; }
                .embed-container iframe {
                    position: absolute; top: 0; left: 0; width: 100% !important; height: 100% !important;
                }
                $cssHide
            </style>
        </head>
        <body>
            <div class="embed-container"><div id="player"></div></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
                var player;
                var isLooping = false;
                var lastState = -1;
                
                function onYouTubeIframeAPIReady() {
                    player = new YT.Player("player", {
                        "width": "100%", "height": "100%", "videoId": "$videoId",
                        "playerVars": {
                            "rel": 0, "showinfo": 0, "modestbranding": 1, "iv_load_policy": 3,
                            "autohide": 1, "autoplay": 1, "playsinline": 1, "controls": 0, "fs": 0,
                            "start": $startTime, "disablekb": 1, "cc_load_policy": 0,
                            "origin": "https://www.youtube-nocookie.com"
                        },
                        "events": { 
                            "onReady": onReady, 
                            "onStateChange": onStateChange, 
                            "onError": onError,
                            "onPlaybackQualityChange": onPlaybackQualityChange
                        }
                    });
                }

                function onReady(event) {
                    if (window.YoutubeProxy) {
                        YoutubeProxy.onPlayerNotifyDuration(player.getDuration());
                        var videoData = player.getVideoData();
                        if (videoData && videoData.title) {
                            YoutubeProxy.onVideoTitle(videoData.title);
                        }
                        updateQualities();
                        YoutubeProxy.onQualityChange(player.getPlaybackQuality());
                        
                        if (player.getPlayerState) {
                            lastState = player.getPlayerState();
                            YoutubeProxy.onPlayerStateChange(lastState);
                        }
                        YoutubeProxy.onPlayerLoaded();
                    }
                    
                    setInterval(function() {
                        pollPosition();
                        checkAds();
                    }, 500);
                    
                    setInterval(hideOverlays, 1000);
                    setupDOMObserver();
                }

                function checkAds() {
                    var skipBtn = document.querySelector('.ytp-ad-skip-button') || 
                                  document.querySelector('.ytp-ad-skip-button-modern') ||
                                  document.querySelector('.videoAdUiSkipButton');
                    if (skipBtn) {
                        skipBtn.click();
                        return;
                    }
                    var adModule = document.querySelector('.ytp-ad-module');
                    if (adModule && adModule.children.length > 0) {
                        adModule.style.display = 'none';
                        var video = document.querySelector('video');
                        if (video && document.querySelector('.ad-showing')) {
                            video.currentTime = video.duration || 9999;
                        }
                    }
                    var overlays = document.querySelectorAll('.ytp-ad-overlay-container, .ytp-ad-image-overlay');
                    overlays.forEach(function(el) { el.remove(); });
                }

                function setupDOMObserver() {
                    var observer = new MutationObserver(function(mutations) {
                        mutations.forEach(function(mutation) {
                            if (mutation.addedNodes.length) {
                                checkAds();
                                hideOverlays();
                            }
                        });
                    });
                    var config = { childList: true, subtree: true };
                    var target = document.body;
                    observer.observe(target, config);
                }

                function hideOverlays() {
                    try {
                        var doc = document;
                        var selectors = [
                            '.ytp-chrome-top', '.ytp-watermark', '.ytp-youtube-button', 
                            '.ytp-ce-element', '.ytp-paid-content-overlay', '.ytp-bezel',
                            '.ytp-upnext', '.ytp-suggestion-set'
                        ];
                        
                        selectors.forEach(function(sel) {
                            var els = doc.querySelectorAll(sel);
                            els.forEach(function(el) { 
                                el.style.display = 'none';
                                el.remove();
                            });
                        });
                    } catch (e) {}
                }

                function onStateChange(event) {
                    lastState = event.data;
                    if (window.YoutubeProxy) {
                        YoutubeProxy.onPlayerStateChange(event.data);
                        updateQualities();
                    }
                    if (event.data === YT.PlayerState.ENDED && isLooping) {
                        player.playVideo();
                    }
                    checkAds();
                    hideOverlays();
                }

                function onPlaybackQualityChange(event) {
                    if (window.YoutubeProxy) {
                        YoutubeProxy.onQualityChange(event.data);
                        updateQualities();
                    }
                }

                function updateQualities() {
                    if (window.YoutubeProxy && player && player.getAvailableQualityLevels) {
                        var qualities = player.getAvailableQualityLevels();
                        if (qualities && qualities.length > 0) {
                            YoutubeProxy.onAvailableQualities(qualities.join(","));
                        }
                    }
                }

                function onError(event) {
                    if (window.YoutubeProxy) YoutubeProxy.onPlayerError(event.data);
                }

                function pollPosition() {
                    if (window.YoutubeProxy && player) {
                        if (player.getCurrentTime) {
                            YoutubeProxy.onPlayerNotifyCurrentPosition(player.getCurrentTime());
                        }
                        if (player.getVideoLoadedFraction) {
                            YoutubeProxy.onBufferedAmount(player.getVideoLoadedFraction());
                        }
                        if (player.getPlayerState) {
                            var currentState = player.getPlayerState();
                            if (currentState !== lastState) {
                                lastState = currentState;
                                YoutubeProxy.onPlayerStateChange(currentState);
                            }
                        }
                    }
                }
                
                function playVideo() { if(player) player.playVideo(); }
                function pauseVideo() { if(player) player.pauseVideo(); }
                function seekTo(time, seekAhead) { if(player) player.seekTo(time, seekAhead); }
                function setPlaybackRate(rate) { if(player) player.setPlaybackRate(rate); }
                
                function setPlaybackQuality(quality) { 
                    if(player) {
                        var targetQuality = quality === 'auto' ? 'default' : quality;
                        if (player.setPlaybackQualityRange) {
                            player.setPlaybackQualityRange(targetQuality, targetQuality);
                        }
                        if (player.setPlaybackQuality) {
                            player.setPlaybackQuality(targetQuality);
                        }
                        var currentTime = player.getCurrentTime();
                        player.seekTo(currentTime, true);
                    }
                }
                
                function mute() { if(player) player.mute(); }
                function unMute() { if(player) player.unMute(); }
                function setLoop(loop) { isLooping = loop; }
                
                function setCaptionsEnabled(enabled) {
                    if (player && player.loadModule) {
                        if (enabled) {
                            player.loadModule("captions");
                            player.setOption("captions", "track", {"languageCode": "en"});
                        } else {
                            player.unloadModule("captions");
                        }
                    }
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}

fun extractYouTubeId(input: String?): String? {
    if (input.isNullOrBlank()) return null
    val rawIdPattern = "^[a-zA-Z0-9_-]{11}$"
    if (Pattern.matches(rawIdPattern, input)) return input
    val urlPattern =
        "(?:youtube(?:-nocookie)?\\.com/(?:[^/\\n\\s]+/\\S+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\\.be/|youtube\\.com/shorts/)([a-zA-Z0-9_-]{11})"
    val matcher = Pattern.compile(urlPattern, Pattern.CASE_INSENSITIVE).matcher(input)
    return if (matcher.find()) matcher.group(1) else null
}

fun extractYouTubeTime(url: String): Int {
    val timePattern = Pattern.compile("[?&]t=(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?")
    val matcher = timePattern.matcher(url)
    if (matcher.find()) {
        val hours = matcher.group(1)?.toIntOrNull() ?: 0
        val minutes = matcher.group(2)?.toIntOrNull() ?: 0
        val seconds = matcher.group(3)?.toIntOrNull() ?: 0

        val simpleTimePattern = Pattern.compile("[?&]t=(\\d+)$")
        val simpleMatcher = simpleTimePattern.matcher(url)
        if (simpleMatcher.find()) {
            return simpleMatcher.group(1)?.toIntOrNull() ?: 0
        }

        return hours * 3600 + minutes * 60 + seconds
    }
    return 0
}