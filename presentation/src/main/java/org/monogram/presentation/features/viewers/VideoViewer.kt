package org.monogram.presentation.features.viewers

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.viewers.components.VideoPage

@OptIn(UnstableApi::class)
@Composable
fun VideoViewer(
    path: String,
    onDismiss: () -> Unit,
    onForward: (String) -> Unit = {},
    onDelete: ((String) -> Unit)? = null,
    onCopyLink: ((String) -> Unit)? = null,
    onCopyText: ((String) -> Unit)? = null,
    onSaveGif: ((String) -> Unit)? = null,
    caption: String? = null,
    fileId: Int = 0,
    supportsStreaming: Boolean = false,
    downloadUtils: IDownloadUtils,
    isGesturesEnabled: Boolean = true,
    isDoubleTapSeekEnabled: Boolean = true,
    seekDuration: Int = 10,
    isZoomEnabled: Boolean = true
) {
    val effectivePath = remember(path, fileId, supportsStreaming) {
        when {
            path.isNotBlank() -> path
            supportsStreaming && fileId != 0 -> "http://streaming/$fileId"
            else -> ""
        }
    }

    if (effectivePath.isBlank()) {
        onDismiss()
        return
    }

    val scope = rememberCoroutineScope()
    val hostState = rememberFullscreenViewerHostState()

    var showControls by remember { mutableStateOf(true) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var currentVideoInPipMode by remember { mutableStateOf(false) }

    LaunchedEffect(effectivePath, fileId, supportsStreaming) {
        hostState.zoomState.resetInstant(scope)
        hostState.rootState.resetInstant(scope)
        showSettingsMenu = false
        currentVideoInPipMode = false
    }

    FullscreenViewerHost(
        onDismiss = onDismiss,
        showControls = showControls,
        showSettingsMenu = showSettingsMenu,
        isInPictureInPicture = currentVideoInPipMode,
        onCloseSettingsMenu = { showSettingsMenu = false },
        hostState = hostState
    ) {
        VideoPage(
            path = effectivePath,
            fileId = fileId,
            caption = caption,
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
            isActive = true,
            onCurrentVideoPipModeChanged = { currentVideoInPipMode = it },
            zoomState = zoomState,
            rootState = rootState,
            screenHeightPx = screenHeightPx,
            dismissDistancePx = dismissDistancePx,
            dismissVelocityThreshold = dismissVelocityThreshold
        )
    }
}
