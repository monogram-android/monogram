package org.monogram.presentation.features.viewers

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.getMimeType
import org.monogram.presentation.features.viewers.components.*

private const val TAG = "MediaViewer"

@OptIn(ExperimentalFoundationApi::class, UnstableApi::class)
@Composable
fun MediaViewer(
    mediaItems: List<String>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
    autoDownload: Boolean = true,
    onPageChanged: ((Int) -> Unit)? = null,
    onForward: (String) -> Unit = {},
    onDelete: ((String) -> Unit)? = null,
    onCopyLink: ((String) -> Unit)? = null,
    onCopyText: ((String) -> Unit)? = null,
    onSaveGif: ((String) -> Unit)? = null,
    captions: List<String?> = emptyList(),
    fileIds: List<Int> = emptyList(),
    imageDownloadingStates: List<Boolean> = emptyList(),
    imageDownloadProgressStates: List<Float> = emptyList(),
    supportsStreaming: Boolean = false,
    downloadUtils: IDownloadUtils,
    showImageNumber: Boolean = true,
    isGesturesEnabled: Boolean = true,
    isDoubleTapSeekEnabled: Boolean = true,
    seekDuration: Int = 10,
    isZoomEnabled: Boolean = true,
    isAlwaysVideo: Boolean = false
) {
    require(mediaItems.isNotEmpty()) { "mediaItems can't be empty" }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { mediaItems.size }
    )

    val rootState = rememberDismissRootState()
    val zoomState = rememberZoomState()

    var showControls by remember { mutableStateOf(true) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var currentVideoInPipMode by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }
    val dismissDistancePx = with(density) { 160.dp.toPx() }
    val dismissVelocityThreshold = with(density) { 1000.dp.toPx() }

    LaunchedEffect(Unit) {
        Log.d(TAG, "Opened with ${mediaItems.size} items, startIndex=$startIndex")
        launch {
            rootState.scale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium))
        }
        launch {
            rootState.backgroundAlpha.animateTo(1f, tween(150))
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        Log.d(TAG, "Page changed to ${pagerState.currentPage}")
        onPageChanged?.invoke(pagerState.currentPage)
        zoomState.resetInstant(scope)
        rootState.resetInstant(scope)
        showSettingsMenu = false
        currentVideoInPipMode = false
    }

    LaunchedEffect(showControls, currentVideoInPipMode) {
        if (!showControls) {
            showSettingsMenu = false
        }

        val activity = context.findActivity()
        activity?.let {
            val insetsController = WindowCompat.getInsetsController(it.window, it.window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            if (showControls && !currentVideoInPipMode) {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(context) {
        onDispose {
            context.findActivity()?.let {
                WindowCompat.getInsetsController(it.window, it.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler {
        Log.d(TAG, "BackHandler: showSettingsMenu=$showSettingsMenu, currentVideoInPipMode=$currentVideoInPipMode")
        if (showSettingsMenu) {
            showSettingsMenu = false
        } else {
            if (currentVideoInPipMode) {
                context.findActivity()?.finishAndRemoveTask()
            } else {
                onDismiss()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = rootState.backgroundAlpha.value))
            .graphicsLayer {
                translationY = rootState.offsetY.value
                scaleX = rootState.scale.value
                scaleY = rootState.scale.value
            }
    ) {
        HorizontalPager(
            state = pagerState,
            key = { page -> "media_page_${page}" },
            pageSize = PageSize.Fill,
            pageSpacing = 0.dp,
            beyondViewportPageCount = 0,
            userScrollEnabled = zoomState.scale.value == 1f && rootState.offsetY.value == 0f
        ) { page ->
            val path = mediaItems.getOrNull(page) ?: return@HorizontalPager
            val mimeType = getMimeType(path)
            val isVideo = (isAlwaysVideo && path.isNotBlank()) || isVideoPath(path, mimeType)

            if (isVideo) {
                VideoPage(
                    path = path,
                    fileId = fileIds.getOrNull(page) ?: 0,
                    caption = captions.getOrNull(page),
                    supportsStreaming = supportsStreaming,
                    downloadUtils = downloadUtils,
                    onDismiss = onDismiss,
                    showControls = showControls,
                    onToggleControls = { showControls = !showControls },
                    onForward = onForward,
                    onDelete = onDelete,
                    onCopyLink = onCopyLink,
                    onCopyText = onCopyText,
                    onSaveGif = onSaveGif,
                    showSettingsMenu = showSettingsMenu,
                    onToggleSettings = { showSettingsMenu = !showSettingsMenu },
                    isGesturesEnabled = isGesturesEnabled,
                    isDoubleTapSeekEnabled = isDoubleTapSeekEnabled,
                    seekDuration = seekDuration,
                    isZoomEnabled = isZoomEnabled,
                    isActive = pagerState.currentPage == page,
                    onCurrentVideoPipModeChanged = { inPip ->
                        if (pagerState.currentPage == page) {
                            currentVideoInPipMode = inPip
                        }
                    },
                    zoomState = zoomState,
                    rootState = rootState,
                    screenHeightPx = screenHeightPx,
                    dismissDistancePx = dismissDistancePx,
                    dismissVelocityThreshold = dismissVelocityThreshold
                )
            } else {
                ImagePage(
                    path = path,
                    isDownloading = imageDownloadingStates.getOrNull(page) == true,
                    downloadProgress = imageDownloadProgressStates.getOrNull(page) ?: 0f,
                    zoomState = zoomState,
                    rootState = rootState,
                    screenHeightPx = screenHeightPx,
                    dismissDistancePx = dismissDistancePx,
                    dismissVelocityThreshold = dismissVelocityThreshold,
                    onDismiss = onDismiss,
                    showControls = showControls,
                    onToggleControls = { showControls = !showControls },
                    pageIndex = page,
                    pagerIndex = pagerState.currentPage
                )
            }
        }

        val currentPath = mediaItems.getOrNull(pagerState.currentPage) ?: ""
        val currentMimeType = getMimeType(currentPath)
        val isCurrentVideo = (isAlwaysVideo && currentPath.isNotBlank()) || isVideoPath(currentPath, currentMimeType)

        if (!isCurrentVideo) {
            ImageOverlay(
                showControls = showControls,
                rootState = rootState,
                pagerState = pagerState,
                mediaItems = mediaItems,
                captions = captions,
                showImageNumber = showImageNumber,
                onDismiss = onDismiss,
                showSettingsMenu = showSettingsMenu,
                onToggleSettings = { showSettingsMenu = !showSettingsMenu },
                downloadUtils = downloadUtils,
                onForward = onForward,
                onDelete = onDelete,
                onCopyLink = onCopyLink,
                onCopyText = onCopyText
            )
        }
    }
}

private fun isVideoPath(path: String, mimeType: String?): Boolean {
    if (path.isBlank()) return false
    if (mimeType?.startsWith("image/") == true) return false

    return mimeType?.startsWith("video/") == true ||
            path.endsWith(".mp4", ignoreCase = true) ||
            path.endsWith(".mkv", ignoreCase = true) ||
            path.endsWith(".mov", ignoreCase = true) ||
            path.endsWith(".webm", ignoreCase = true) ||
            path.endsWith(".avi", ignoreCase = true) ||
            path.endsWith(".3gp", ignoreCase = true) ||
            path.endsWith(".m4v", ignoreCase = true)
}
