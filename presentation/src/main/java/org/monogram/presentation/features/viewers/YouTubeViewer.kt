@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.viewers

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import org.monogram.presentation.R
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.features.viewers.components.GestureOverlay
import org.monogram.presentation.features.viewers.components.PipController
import org.monogram.presentation.features.viewers.components.SeekFeedback
import org.monogram.presentation.features.viewers.components.YouTubePlayerControlsUI
import org.monogram.presentation.features.viewers.components.YouTubeSettingsMenu
import org.monogram.presentation.features.viewers.components.baseQualityLabel
import org.monogram.presentation.features.viewers.components.enterPipMode
import org.monogram.presentation.features.viewers.components.findActivity
import org.monogram.presentation.features.viewers.components.normalizeYouTubeQualityCode
import org.monogram.presentation.features.viewers.components.youtubeQualityRank
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max

private const val QUALITY_STABILIZATION_TIMEOUT_MS = 4000L

enum class PlayerErrorCategory {
    VIDEO_NOT_FOUND,
    EMBEDDING_FORBIDDEN,
    HTML5,
    UNKNOWN
}

sealed class QualityStatus {
    object Stable : QualityStatus()
    object Recovering : QualityStatus()
    object Downgraded : QualityStatus()
    object UnavailableAtRequest : QualityStatus()
    data class PlayerError(val category: PlayerErrorCategory, val code: Int) : QualityStatus()
}

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
    val isDebuggableBuild = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
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
                setSupportMultipleWindows(false)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        view?.loadUrl(url)
                    }
                    return true
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val targetUrl = url.orEmpty()
                    if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
                        view?.loadUrl(targetUrl)
                    }
                    return true
                }

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
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val popupWebView = WebView(context)
                    popupWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            nestedView: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val targetUrl = request?.url?.toString().orEmpty()
                            if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
                                popupWebView.destroy()
                                view?.loadUrl(targetUrl)
                            }
                            return true
                        }
                    }
                    transport.webView = popupWebView
                    resultMsg.sendToTarget()
                    return true
                }
            }
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
        playerState.resetForNewVideo()
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

    LaunchedEffect(playerState.qualityRequestedAtMs, playerState.currentQuality) {
        val requestedAt = playerState.qualityRequestedAtMs ?: return@LaunchedEffect
        delay(QUALITY_STABILIZATION_TIMEOUT_MS)
        if (playerState.qualityRequestedAtMs == requestedAt) {
            playerState.recalculateQualityStatus()
        }
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

    fun requestQuality(quality: String) {
        val normalized = normalizeYouTubeQualityCode(quality)
        playerState.onQualityRequested(normalized)
        webView.evaluateJavascript("setPlaybackQuality('$normalized')", null)
    }

    fun retryCurrentQuality() {
        val requested = normalizeYouTubeQualityCode(playerState.currentQuality)
        requestQuality(if (requested.isBlank()) "auto" else requested)
    }

    fun switchToAutoQuality() {
        requestQuality("auto")
    }

    fun useLowQuality() {
        val available = playerState.availableQualities
        val target = when {
            "small" in available -> "small"
            "medium" in available -> "medium"
            "large" in available -> "large"
            else -> "auto"
        }
        requestQuality(target)
    }

    fun openInBrowser() {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)))
        }
    }

    val qualityStatusMessage = qualityStatusMessage(playerState.qualityStatus)
    val showRetryStream = playerState.qualityStatus is QualityStatus.Downgraded
    val showSwitchToAuto = playerState.qualityStatus is QualityStatus.Downgraded ||
            playerState.qualityStatus is QualityStatus.UnavailableAtRequest
    val showUseLowQuality = playerState.qualityStatus is QualityStatus.Downgraded
    val showOpenInBrowser = playerState.qualityStatus is QualityStatus.PlayerError

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

        if (isDebuggableBuild && !isInPipMode) {
            YouTubeDebugInfo(
                state = playerState,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(16.dp)
            )
        }

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
                                        (lp.screenBrightness.takeIf { it != -1f }
                                            ?: 0.5f) - (dragAmount / 1000f)
                                    newBrightness = newBrightness.coerceIn(0f, 1f)
                                    lp.screenBrightness = newBrightness
                                    activity.window.attributes = lp
                                    gestureIcon = Icons.Rounded.BrightnessMedium
                                    gestureText = "${(newBrightness * 100).toInt()}%"
                                } else {
                                    val audioManager =
                                        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                    val maxVol =
                                        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val currentVol =
                                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val delta = -(dragAmount / 50f)
                                    val newVol = (currentVol + delta).coerceIn(0f, maxVol.toFloat())
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        newVol.toInt(),
                                        0
                                    )
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
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainer,
                                        CircleShape
                                    )
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
                                appliedQuality = playerState.appliedQuality,
                                qualityStatusMessage = qualityStatusMessage,
                                showRetryStream = showRetryStream,
                                showSwitchToAuto = showSwitchToAuto,
                                showUseLowQuality = showUseLowQuality,
                                showOpenInBrowser = showOpenInBrowser,
                                onQualitySelected = {
                                    requestQuality(it)
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
                                onForward = { onForward(videoUrl); showSettingsMenu = false },
                                onRetryStream = { retryCurrentQuality() },
                                onSwitchToAuto = { switchToAutoQuality() },
                                onUseLowQuality = { useLowQuality() },
                                onOpenInBrowser = { openInBrowser() }
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
    var availableQualitiesAtRequest by mutableStateOf<List<String>>(emptyList())
    var currentQuality by mutableStateOf("auto")
    var appliedQuality by mutableStateOf("auto")
    var playerStateCode by mutableIntStateOf(-1)
    var lastErrorCode by mutableStateOf<Int?>(null)
    var qualityRequestedAtMs by mutableStateOf<Long?>(null)
    var qualityAppliedAtMs by mutableStateOf<Long?>(null)
    var qualityStatus by mutableStateOf<QualityStatus>(QualityStatus.Stable)
    var qualityStatusSinceMs by mutableStateOf<Long?>(null)
    var isRecovering by mutableStateOf(false)

    fun onQualityRequested(quality: String) {
        currentQuality = quality
        availableQualitiesAtRequest = availableQualities
        qualityRequestedAtMs = System.currentTimeMillis()
        lastErrorCode = null
        updateQualityStatus(QualityStatus.Recovering)
        isRecovering = true
    }

    fun recalculateQualityStatus(nowMs: Long = System.currentTimeMillis()) {
        val requested = normalizeYouTubeQualityCode(currentQuality)
        val applied = normalizeYouTubeQualityCode(appliedQuality)
        val requestedAt = qualityRequestedAtMs
        val isRequestedAvailable = requested in availableQualitiesAtRequest

        val newStatus = when {
            lastErrorCode != null -> {
                QualityStatus.PlayerError(
                    category = mapErrorCodeToCategory(lastErrorCode!!),
                    code = lastErrorCode!!
                )
            }

            requested == "auto" -> QualityStatus.Stable
            requestedAt != null && (nowMs - requestedAt) < QUALITY_STABILIZATION_TIMEOUT_MS && requested != applied -> {
                QualityStatus.Recovering
            }

            requested == applied -> QualityStatus.Stable
            !isRequestedAvailable -> QualityStatus.UnavailableAtRequest
            else -> QualityStatus.Downgraded
        }

        isRecovering = newStatus is QualityStatus.Recovering
        updateQualityStatus(newStatus)
    }

    private fun updateQualityStatus(status: QualityStatus) {
        if (qualityStatus != status) {
            qualityStatus = status
            qualityStatusSinceMs = System.currentTimeMillis()
        } else if (qualityStatusSinceMs == null) {
            qualityStatusSinceMs = System.currentTimeMillis()
        }
    }

    fun resetForNewVideo() {
        isPlaying = false
        currentTime = 0f
        duration = 0f
        isLoading = true
        videoTitle = ""
        bufferedAmount = 0f
        availableQualities = emptyList()
        availableQualitiesAtRequest = emptyList()
        currentQuality = "auto"
        appliedQuality = "auto"
        playerStateCode = -1
        lastErrorCode = null
        qualityRequestedAtMs = null
        qualityAppliedAtMs = null
        qualityStatus = QualityStatus.Stable
        qualityStatusSinceMs = null
        isRecovering = false
    }
}

class YoutubeProxy(
    private val state: YouTubePlayerState,
    private val onLoaded: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onPlayerStateChange(playerState: Int) {
        handler.post {
            state.playerStateCode = playerState
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
        handler.post {
            state.lastErrorCode = error
            state.recalculateQualityStatus()
        }
    }

    @JavascriptInterface
    fun onAvailableQualities(qualities: String) {
        handler.post {
            state.availableQualities = normalizeQualityLevels(qualities.split(","))
            state.recalculateQualityStatus()
        }
    }

    @JavascriptInterface
    fun onQualityChange(quality: String) {
        handler.post {
            state.appliedQuality = normalizeReportedQuality(quality, state.currentQuality)
            state.qualityAppliedAtMs = System.currentTimeMillis()
            state.recalculateQualityStatus()
        }
    }
}

@Composable
private fun YouTubeDebugInfo(
    state: YouTubePlayerState,
    modifier: Modifier = Modifier
) {
    val bufferedSeconds = (state.bufferedAmount * state.duration).coerceAtLeast(0f)
    val errorLabel = state.lastErrorCode?.let(::youtubeErrorLabel) ?: "none"
    val qualityFallback = isQualityFallback(
        requestedQuality = state.currentQuality,
        appliedQuality = state.appliedQuality
    )
    val requestAvailable =
        state.currentQuality == "auto" || state.currentQuality in state.availableQualities
    val requestAvailableAtRequest =
        state.currentQuality == "auto" || state.currentQuality in state.availableQualitiesAtRequest
    val fallbackReason = qualityFallbackReason(
        requestedQuality = state.currentQuality,
        appliedQuality = state.appliedQuality,
        requestAvailableAtRequest = requestAvailableAtRequest,
        errorCode = state.lastErrorCode
    )
    val debugLines = listOf(
        "DEBUG",
        "state=${youtubePlayerStateLabel(state.playerStateCode)}(${state.playerStateCode}) loading=${state.isLoading}",
        "quality requested=${formatDebugQualityLabel(state.currentQuality)} applied=${
            formatDebugQualityLabel(
                state.appliedQuality
            )
        }",
        "quality fallback=$qualityFallback reason=$fallbackReason",
        "quality req_available_at_request=$requestAvailableAtRequest available_now=$requestAvailable",
        "quality req_at=${formatDebugTimestamp(state.qualityRequestedAtMs)} applied_at=${
            formatDebugTimestamp(
                state.qualityAppliedAtMs
            )
        }",
        "qualities_at_request=${
            state.availableQualitiesAtRequest.joinToString(",") {
                formatDebugQualityLabel(
                    it
                )
            }.ifBlank { "none" }
        }",
        "qualities=${
            state.availableQualities.joinToString(",") { formatDebugQualityLabel(it) }
                .ifBlank { "none" }
        }",
        "time=${"%.1f".format(Locale.US, state.currentTime)}/${
            "%.1f".format(
                Locale.US,
                state.duration
            )
        } buffer=${"%.1f".format(Locale.US, bufferedSeconds)}",
        "speed=${state.playbackSpeed} muted=${state.isMuted} loop=${state.isLooping} cc=${state.isCaptionsEnabled}",
        "error=$errorLabel"
    )

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = debugLines.joinToString("\n"),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color.White
        )
    }
}

private fun isQualityFallback(
    requestedQuality: String,
    appliedQuality: String
): Boolean {
    val requested = normalizeYouTubeQualityCode(requestedQuality)
    val applied = normalizeYouTubeQualityCode(appliedQuality)
    return requested.isNotBlank() &&
            requested != "auto" &&
            applied.isNotBlank() &&
            requested != applied
}

private fun qualityFallbackReason(
    requestedQuality: String,
    appliedQuality: String,
    requestAvailableAtRequest: Boolean,
    errorCode: Int?
): String {
    val requested = normalizeYouTubeQualityCode(requestedQuality)
    val applied = normalizeYouTubeQualityCode(appliedQuality)

    return when {
        errorCode != null -> "player_error_${youtubeErrorLabel(errorCode)}"
        requested.isBlank() || requested == "auto" -> "none"
        applied.isBlank() -> "unknown"
        requested == applied -> "none"
        !requestAvailableAtRequest -> "unavailable_at_request"
        else -> "youtube_downgrade"
    }
}

@Composable
private fun qualityStatusMessage(status: QualityStatus): String? {
    return when (status) {
        QualityStatus.Stable -> null
        QualityStatus.Recovering -> stringResource(R.string.quality_status_recovering)
        QualityStatus.Downgraded -> stringResource(R.string.quality_status_downgraded)
        QualityStatus.UnavailableAtRequest -> stringResource(R.string.quality_status_unavailable_at_request)
        is QualityStatus.PlayerError -> when (status.category) {
            PlayerErrorCategory.VIDEO_NOT_FOUND -> stringResource(R.string.quality_status_error_video_not_found)
            PlayerErrorCategory.EMBEDDING_FORBIDDEN -> stringResource(R.string.quality_status_error_embedding_forbidden)
            PlayerErrorCategory.HTML5 -> stringResource(R.string.quality_status_error_html5)
            PlayerErrorCategory.UNKNOWN -> stringResource(
                R.string.quality_status_error_unknown,
                status.code
            )
        }
    }
}

private fun formatDebugTimestamp(timestampMs: Long?): String {
    if (timestampMs == null) return "none"
    return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
}

private fun formatDebugQualityLabel(quality: String): String {
    val normalized = normalizeYouTubeQualityCode(quality)
    val pretty = baseQualityLabel(quality)

    return when {
        normalized.isBlank() -> "unknown"
        pretty.equals(quality, ignoreCase = true) -> pretty
        else -> "$pretty [$quality]"
    }
}

private fun normalizeQualityLevels(qualities: List<String>): List<String> {
    return qualities
        .map(::normalizeYouTubeQualityCode)
        .filter { it.isNotBlank() && it != "default" && it != "unknown" && it != "auto" }
        .distinct()
        .sortedByDescending(::youtubeQualityRank)
}

private fun normalizeReportedQuality(
    quality: String,
    selectedQuality: String
): String {
    val normalized = normalizeYouTubeQualityCode(quality)
    return when {
        normalized.isBlank() || normalized == "default" || normalized == "unknown" -> {
            if (normalizeYouTubeQualityCode(selectedQuality) == "auto") "auto" else normalizeYouTubeQualityCode(
                selectedQuality
            )
        }

        else -> normalized
    }
}

private fun youtubePlayerStateLabel(stateCode: Int): String {
    return when (stateCode) {
        -1 -> "UNSTARTED"
        0 -> "ENDED"
        1 -> "PLAYING"
        2 -> "PAUSED"
        3 -> "BUFFERING"
        5 -> "CUED"
        else -> "UNKNOWN"
    }
}

private fun youtubeErrorLabel(errorCode: Int): String {
    return when (errorCode) {
        2 -> "invalid_parameter"
        5 -> "html5_error"
        100 -> "video_not_found"
        101, 150 -> "embedding_forbidden"
        else -> "unknown_$errorCode"
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
                var preferredQuality = "auto";
                
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
                        notifyQuality();
                        
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
                        notifyQuality();
                        updateQualities();
                    }
                }

                function getReportedQuality() {
                    if (!player || !player.getPlaybackQuality) return preferredQuality;
                    var quality = player.getPlaybackQuality();
                    if (!quality || quality === "default" || quality === "unknown") {
                        return preferredQuality;
                    }
                    return quality;
                }

                function notifyQuality() {
                    if (window.YoutubeProxy) {
                        YoutubeProxy.onQualityChange(getReportedQuality());
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
                        notifyQuality();
                    }
                }
                
                function playVideo() { if(player) player.playVideo(); }
                function pauseVideo() { if(player) player.pauseVideo(); }
                function seekTo(time, seekAhead) { if(player) player.seekTo(time, seekAhead); }
                function setPlaybackRate(rate) { if(player) player.setPlaybackRate(rate); }
                
                function setPlaybackQuality(quality) { 
                    if(player) {
                        preferredQuality = quality || "auto";
                        var targetQuality = preferredQuality === "auto" ? "default" : preferredQuality;
                        var currentTime = player.getCurrentTime ? player.getCurrentTime() : 0;
                        var wasPlaying = player.getPlayerState && player.getPlayerState() === YT.PlayerState.PLAYING;
                        var videoData = player.getVideoData ? player.getVideoData() : null;
                        var targetVideoId = videoData && videoData.video_id ? videoData.video_id : "$videoId";

                        if (player.setPlaybackQualityRange) {
                            player.setPlaybackQualityRange(targetQuality, targetQuality);
                        }
                        if (player.setPlaybackQuality) {
                            player.setPlaybackQuality(targetQuality);
                        }
                        if (player.loadVideoById && targetVideoId) {
                            player.loadVideoById({
                                videoId: targetVideoId,
                                startSeconds: currentTime,
                                suggestedQuality: targetQuality
                            });
                            if (!wasPlaying && player.pauseVideo) {
                                setTimeout(function() {
                                    player.pauseVideo();
                                }, 150);
                            }
                        } else if (player.seekTo) {
                            player.seekTo(currentTime, true);
                        }
                        setTimeout(function() {
                            notifyQuality();
                            updateQualities();
                        }, 300);
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

private fun mapErrorCodeToCategory(errorCode: Int): PlayerErrorCategory {
    return when (errorCode) {
        100 -> PlayerErrorCategory.VIDEO_NOT_FOUND
        101, 150 -> PlayerErrorCategory.EMBEDDING_FORBIDDEN
        5 -> PlayerErrorCategory.HTML5
        else -> PlayerErrorCategory.UNKNOWN
    }
}
