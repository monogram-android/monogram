package org.monogram.presentation.features.viewers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.viewers.components.ImageOverlay
import org.monogram.presentation.features.viewers.components.ImagePage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewer(
    images: List<String>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
    autoDownload: Boolean = true,
    onPageChanged: ((Int) -> Unit)? = null,
    onForward: (String) -> Unit = {},
    onDelete: ((String) -> Unit)? = null,
    onCopyLink: ((String) -> Unit)? = null,
    onCopyText: ((String) -> Unit)? = null,
    onVideoClick: ((String) -> Unit)? = null,
    captions: List<String?> = emptyList(),
    imageDownloadingStates: List<Boolean> = emptyList(),
    imageDownloadProgressStates: List<Float> = emptyList(),
    downloadUtils: IDownloadUtils,
    showImageNumber: Boolean = true
) {
    require(images.isNotEmpty()) { "images can't be empty" }

    val resolvedIndex = startIndex.coerceIn(0, images.lastIndex.coerceAtLeast(0))
    val pagerState = rememberPagerState(
        initialPage = resolvedIndex,
        pageCount = { images.size }
    )
    val scope = rememberCoroutineScope()
    val hostState = rememberFullscreenViewerHostState()

    var showControls by remember { mutableStateOf(true) }
    var showSettingsMenu by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged?.invoke(pagerState.currentPage)
        hostState.zoomState.resetInstant(scope)
        hostState.rootState.resetInstant(scope)
        showSettingsMenu = false
    }

    FullscreenViewerHost(
        onDismiss = onDismiss,
        showControls = showControls,
        showSettingsMenu = showSettingsMenu,
        onCloseSettingsMenu = { showSettingsMenu = false },
        hostState = hostState
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                key = { page ->
                    val path = images.getOrNull(page).orEmpty()
                    "image_page_${path}_$page"
                },
                pageSize = PageSize.Fill,
                beyondViewportPageCount = 0,
                userScrollEnabled = zoomState.scale.value == 1f && rootState.offsetY.value == 0f
            ) { page ->
                val path = images.getOrNull(page) ?: return@HorizontalPager

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                ) {
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

            ImageOverlay(
                showControls = showControls,
                rootState = rootState,
                pagerState = pagerState,
                mediaItems = images,
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
