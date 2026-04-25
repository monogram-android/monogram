package org.monogram.presentation.features.viewers

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.getMimeType
import org.monogram.presentation.features.viewers.components.DismissRootState
import org.monogram.presentation.features.viewers.components.ZoomState
import org.monogram.presentation.features.viewers.components.findActivity
import org.monogram.presentation.features.viewers.components.rememberDismissRootState
import org.monogram.presentation.features.viewers.components.rememberZoomState

internal data class FullscreenViewerHostState(
    val rootState: DismissRootState,
    val zoomState: ZoomState,
    val screenHeightPx: Float,
    val dismissDistancePx: Float,
    val dismissVelocityThreshold: Float
)

@Composable
internal fun rememberFullscreenViewerHostState(): FullscreenViewerHostState {
    val rootState = rememberDismissRootState()
    val zoomState = rememberZoomState()
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current

    return remember(rootState, zoomState, containerSize, density) {
        FullscreenViewerHostState(
            rootState = rootState,
            zoomState = zoomState,
            screenHeightPx = containerSize.height.toFloat(),
            dismissDistancePx = with(density) { 160.dp.toPx() },
            dismissVelocityThreshold = with(density) { 1000.dp.toPx() }
        )
    }
}

@Composable
internal fun FullscreenViewerHost(
    onDismiss: () -> Unit,
    showControls: Boolean,
    showSettingsMenu: Boolean = false,
    isInPictureInPicture: Boolean = false,
    onCloseSettingsMenu: (() -> Unit)? = null,
    hostState: FullscreenViewerHostState = rememberFullscreenViewerHostState(),
    content: @Composable FullscreenViewerHostState.() -> Unit
) {
    val context = LocalContext.current
    val currentOnCloseSettingsMenu = onCloseSettingsMenu

    LaunchedEffect(Unit) {
        launch {
            hostState.rootState.scale.animateTo(
                1f,
                spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
            )
        }
        launch {
            hostState.rootState.backgroundAlpha.animateTo(1f, tween(150))
        }
    }

    LaunchedEffect(showControls, isInPictureInPicture) {
        if (!showControls) {
            currentOnCloseSettingsMenu?.invoke()
        }

        context.findActivity()?.let {
            val insetsController = WindowCompat.getInsetsController(it.window, it.window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            if (showControls && !isInPictureInPicture) {
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
        if (showSettingsMenu) {
            currentOnCloseSettingsMenu?.invoke()
        } else if (isInPictureInPicture) {
            context.findActivity()?.finishAndRemoveTask()
        } else {
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = hostState.rootState.backgroundAlpha.value))
            .graphicsLayer {
                translationY = hostState.rootState.offsetY.value
                scaleX = hostState.rootState.scale.value
                scaleY = hostState.rootState.scale.value
            }
    ) {
        hostState.content()
    }
}

@OptIn(UnstableApi::class)
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

    val resolvedIndex = startIndex.coerceIn(0, mediaItems.lastIndex.coerceAtLeast(0))
    val currentPath = mediaItems[resolvedIndex]
    val currentMimeType = getMimeType(currentPath)
    val shouldRenderSingleVideo =
        mediaItems.size == 1 && ((isAlwaysVideo && currentPath.isNotBlank()) || isVideoPath(
            currentPath,
            currentMimeType
        ))

    if (shouldRenderSingleVideo) {
        VideoViewer(
            path = currentPath,
            onDismiss = onDismiss,
            onForward = onForward,
            onDelete = onDelete,
            onCopyLink = onCopyLink,
            onCopyText = onCopyText,
            onSaveGif = onSaveGif,
            caption = captions.getOrNull(resolvedIndex),
            fileId = fileIds.getOrNull(resolvedIndex) ?: 0,
            supportsStreaming = supportsStreaming,
            downloadUtils = downloadUtils,
            isGesturesEnabled = isGesturesEnabled,
            isDoubleTapSeekEnabled = isDoubleTapSeekEnabled,
            seekDuration = seekDuration,
            isZoomEnabled = isZoomEnabled
        )
        return
    }

    ImageViewer(
        images = mediaItems,
        startIndex = resolvedIndex,
        onDismiss = onDismiss,
        autoDownload = autoDownload,
        onPageChanged = onPageChanged,
        onForward = onForward,
        onDelete = onDelete,
        onCopyLink = onCopyLink,
        onCopyText = onCopyText,
        captions = captions,
        imageDownloadingStates = imageDownloadingStates,
        imageDownloadProgressStates = imageDownloadProgressStates,
        downloadUtils = downloadUtils,
        showImageNumber = showImageNumber
    )
}

internal fun isVideoPath(path: String, mimeType: String?): Boolean {
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
